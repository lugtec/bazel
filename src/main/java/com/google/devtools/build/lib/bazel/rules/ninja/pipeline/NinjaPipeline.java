// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.rules.ninja.pipeline;

import static com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGetWithCheckedException;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.lib.bazel.rules.ninja.file.ByteFragmentAtOffset;
import com.google.devtools.build.lib.bazel.rules.ninja.file.CollectingListFuture;
import com.google.devtools.build.lib.bazel.rules.ninja.file.GenericParsingException;
import com.google.devtools.build.lib.bazel.rules.ninja.file.NinjaSeparatorFinder;
import com.google.devtools.build.lib.bazel.rules.ninja.file.ParallelFileProcessing;
import com.google.devtools.build.lib.bazel.rules.ninja.file.ParallelFileProcessing.BlockParameters;
import com.google.devtools.build.lib.bazel.rules.ninja.lexer.NinjaLexer;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaFileParseResult;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaFileParseResult.NinjaPromise;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaParser;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaParserStep;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScope;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaTarget;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaVariableValue;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * Responsible for parsing Ninja file, all its included and subninja files, and returning {@link
 * NinjaScope} with rules and expanded variables, and list of {@link NinjaTarget}.
 *
 * <p>Uses provided {@link ListeningExecutorService} for scheduling tasks in parallel.
 */
public class NinjaPipeline {
  private final Path basePath;
  private final ListeningExecutorService service;

  /**
   * @param basePath base path for resolving include and subninja paths.
   * @param service service to use for scheduling tasks in parallel.
   */
  public NinjaPipeline(Path basePath, ListeningExecutorService service) {
    this.basePath = basePath;
    this.service = service;
  }

  /**
   * Parses <code>mainFile</code> and all it's children from include and subninja statements.
   *
   * @return {@link Pair} of {@link NinjaScope} with rules and expanded variables (and child
   *     scopes), and list of {@link NinjaTarget}.
   */
  public Pair<NinjaScope, List<NinjaTarget>> pipeline(Path mainFile)
      throws GenericParsingException, InterruptedException, IOException {
    NinjaFileParseResult result =
        waitForFutureAndGetWithCheckedException(
            scheduleParsing(mainFile), GenericParsingException.class, IOException.class);

    Map<NinjaScope, List<ByteFragmentAtOffset>> rawTargets = Maps.newHashMap();
    NinjaScope scope = new NinjaScope();
    // This will cause additional parsing of included/subninja scopes, and their recursive expand.
    result.expandIntoScope(scope, rawTargets);
    return Pair.of(scope, iterateScopesScheduleTargetsParsing(scope, rawTargets));
  }

  /**
   * Each NinjaTarget should be parsed in the context of it's parent {@link NinjaScope}. (All the
   * variables in targets are immediately expanded.) We are iterating main and all transitively
   * included scopes, and parsing corresponding targets.
   */
  private List<NinjaTarget> iterateScopesScheduleTargetsParsing(
      NinjaScope scope, Map<NinjaScope, List<ByteFragmentAtOffset>> rawTargets)
      throws GenericParsingException, InterruptedException {
    ArrayDeque<NinjaScope> queue = new ArrayDeque<>();
    queue.add(scope);
    CollectingListFuture<NinjaTarget, GenericParsingException> future =
        new CollectingListFuture<>(GenericParsingException.class);
    while (!queue.isEmpty()) {
      NinjaScope currentScope = queue.removeFirst();
      List<ByteFragmentAtOffset> targetFragments = rawTargets.get(currentScope);
      Preconditions.checkNotNull(targetFragments);
      for (ByteFragmentAtOffset fragment : targetFragments) {
        future.add(
            service.submit(
                () ->
                    new NinjaParserStep(new NinjaLexer(fragment.getFragment()))
                        .parseNinjaTarget(currentScope, fragment.getRealStartOffset())));
      }
      queue.addAll(currentScope.getIncludedScopes());
      queue.addAll(currentScope.getSubNinjaScopes());
    }
    return future.getResult();
  }

  /**
   * Synchronously or asynchronously schedules parsing of the included Ninja file, and returns
   * {@link NinjaPromise<NinjaFileParseResult>} - an object, from which we can obtain the result of
   * the parsing - {@link NinjaFileParseResult} - after we resolved variables in the parent file to
   * the point when the child file was included.
   *
   * <p>In some cases, include and subninja statements can contain variable references, then we must
   * postpone scheduling the parsing of the file until variable is expanded and the path to file
   * becomes known.
   *
   * <p>That is why we use {@link NinjaPromise<NinjaFileParseResult>} to save the future file
   * parsing result in the parent file {@link NinjaFileParseResult} structure.
   */
  public NinjaPromise<NinjaFileParseResult> createChildFileParsingPromise(
      NinjaVariableValue value, Integer offset) throws IOException {
    if (value.isPlainText()) {
      // If the value of the path is already known, we can immediately schedule parsing
      // of the child Ninja file.
      Path path = basePath.getRelative(value.getRawText());
      ListenableFuture<NinjaFileParseResult> parsingFuture = scheduleParsing(path);
      return (scope) ->
          waitForFutureAndGetWithCheckedException(
              parsingFuture, GenericParsingException.class, IOException.class);
    } else {
      // If the value of the child path refers some variables in the parent scope, resolve it,
      // when the lambda is called, schedule the parsing and wait for it's completion.
      return (scope) -> {
        String expandedValue = scope.getExpandedValue(offset, value);
        if (expandedValue.isEmpty()) {
          throw new GenericParsingException("Expected non-empty path.");
        }
        Path path = basePath.getRelative(expandedValue);
        return waitForFutureAndGetWithCheckedException(
            scheduleParsing(path), GenericParsingException.class, IOException.class);
      };
    }
  }

  /**
   * Actually schedules the parsing of the Ninja file and returns {@link
   * ListenableFuture<NinjaFileParseResult>} for obtaining the result.
   */
  private ListenableFuture<NinjaFileParseResult> scheduleParsing(Path path) throws IOException {
    BlockParameters parameters = new BlockParameters(path.getFileSize());
    return service.submit(
        () -> {
          try (ReadableByteChannel channel = path.createReadableByteChannel()) {
            List<NinjaFileParseResult> pieces = Lists.newArrayList();
            ParallelFileProcessing.processFile(
                channel,
                parameters,
                () -> {
                  NinjaFileParseResult parseResult = new NinjaFileParseResult();
                  pieces.add(parseResult);
                  return new NinjaParser(NinjaPipeline.this, parseResult);
                },
                service,
                NinjaSeparatorFinder.INSTANCE);
            return NinjaFileParseResult.merge(pieces);
          }
        });
  }
}
