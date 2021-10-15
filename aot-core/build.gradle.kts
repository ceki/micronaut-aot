/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
    id("io.micronaut.build.internal.aot-module")
}

description = "Micronaut AOT source generators"

dependencies {
    api(mn.micronaut.context)
    api(mn.micronaut.inject)
    api(mn.micronaut.core.reactive)
    api(libs.javapoet)
    implementation(mn.logback)
    implementation(mn.graal)
    implementation(mn.graal.sdk)

    testFixturesImplementation(libs.javapoet)
    testFixturesImplementation(mn.spock)
    testFixturesImplementation(mn.micronaut.inject.asProvider()) {
        because("The BOM is not available in the catalog so we need to use an explicit dependency")
    }

    testImplementation(mn.spock)
}
