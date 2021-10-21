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
package io.micronaut.aot.core.sourcegen;

import com.squareup.javapoet.JavaFile;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.io.scan.DefaultClassPathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A source generator which will generate a static {@link io.micronaut.context.env.PropertySource}
 * from a given YAML configuration file, in order to substitute the dynamic loader
 * with a static configuration.
 *
 */
public class YamlPropertySourceGenerator extends AbstractSourceGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(YamlPropertySourceGenerator.class);

    private final Collection<String> resources;

    public YamlPropertySourceGenerator(SourceGenerationContext context,
                                       Collection<String> resources) {
        super(context);
        this.resources = resources;
    }

    @Override
    public List<JavaFile> generateSourceFiles() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<JavaFile> files = new ArrayList<>();

        for (String resource : resources) {
            createMapProperty(loader, files, resource);
        }

        return files;
    }

    private void createMapProperty(YamlPropertySourceLoader loader, List<JavaFile> files, String resource) {
        Optional<PropertySource> optionalSource = loader.load(resource, new DefaultClassPathResourceLoader(this.getClass().getClassLoader()));
        if (optionalSource.isPresent()) {
            LOGGER.info("Converting {} into Java based configuration", resource + ".yml");
            PropertySource ps = optionalSource.get();
            if (ps instanceof MapPropertySource) {
                MapPropertySource mps = (MapPropertySource) ps;
                Map<String, Object> values = mps.asMap();
                MapPropertySourceGenerator generator = new MapPropertySourceGenerator(
                        getContext(),
                        resource,
                        values);
                files.add(generator.generate());
            } else {
                throw new UnsupportedOperationException("Unknown property source type:" + ps.getClass());
            }
        }
    }

}
