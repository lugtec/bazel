/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.build.android.desugar.testing.junit;

import com.google.common.annotations.UsedReflectively;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.zip.ZipEntry;

/**
 * Identifies injectable {@link ZipEntry} fields with a zip entry path. The desugar rule resolves
 * the requested zip entry at runtime and assign it to the annotated field. An injectable {@link
 * ZipEntry} field may have any access modifier (private, package-private, protected, public).
 * Sample usage:
 *
 * <pre><code>
 * &#064;RunWith(JUnit4.class)
 * public class DesugarRuleTest {
 *
 *   &#064;Rule
 *   public final DesugarRule desugarRule =
 *       DesugarRule.builder(this, MethodHandles.lookup())
 *           .addRuntimeInputs("path/to/my_jar.jar")
 *           .build();
 *
 *   &#064;LoadZipEntry("my/package/ClassToDesugar.class")
 *   private ZipEntry classToDesugarClassFile;
 *
 *   // ... Test methods ...
 * }
 * </code></pre>
 */
@UsedReflectively
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoadZipEntry {

  /** The requested zip entry path name within a zip file. */
  String value();

  /** The round during which its associated jar is being used. */
  int round() default 1;
}
