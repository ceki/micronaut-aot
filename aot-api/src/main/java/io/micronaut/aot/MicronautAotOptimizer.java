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
package io.micronaut.aot;

import com.squareup.javapoet.JavaFile;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.aot.core.sourcegen.AbstractSourceGenerator;
import io.micronaut.aot.core.sourcegen.AbstractStaticServiceLoaderSourceGenerator;
import io.micronaut.aot.core.sourcegen.ApplicationContextCustomizerGenerator;
import io.micronaut.aot.core.sourcegen.ConstantPropertySourcesSourceGenerator;
import io.micronaut.aot.core.sourcegen.EnableCachedEnvironmentSourceGenerator;
import io.micronaut.aot.core.sourcegen.EnvironmentPropertiesSourceGenerator;
import io.micronaut.aot.core.sourcegen.GraalVMOptimizationFeatureSourceGenerator;
import io.micronaut.aot.core.sourcegen.JitStaticServiceLoaderSourceGenerator;
import io.micronaut.aot.core.sourcegen.KnownMissingTypesSourceGenerator;
import io.micronaut.aot.core.sourcegen.LogbackConfigurationSourceGenerator;
import io.micronaut.aot.core.sourcegen.NativeStaticServiceLoaderSourceGenerator;
import io.micronaut.aot.core.sourcegen.PublishersSourceGenerator;
import io.micronaut.aot.core.sourcegen.SourceGenerationContext;
import io.micronaut.aot.core.sourcegen.SourceGenerator;
import io.micronaut.aot.core.sourcegen.YamlPropertySourceGenerator;
import io.micronaut.aot.internal.StreamHelper;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Experimental;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The Micronaut AOT optimizer is the main entry point for code
 * generation at build time. Its role is to generate a bunch of
 * source code for various optimizations which can be computed
 * at build time.
 *
 * Typically, generated code will involve the generation of an
 * "optimized" entry point for the application, which delegates
 * to the main entry point, but also performs some static
 * initialization by making calls to the
 * {@link io.micronaut.core.optim.StaticOptimizations} class.
 *
 * The Micronaut AOT optimizer is experimental and won't do
 * anything by its own: it must be integrated in some form, for
 * example via a build plugin, which in turn will make the generated
 * classes visible to the user. For example, the build tool may
 * call this class to generate the optimization code, and in addition
 * create an optimized jar, an optimized native binary or even a
 * full distribution.
 *
 * The optimizer works by passing in the whole application runtime
 * classpath and a set of configuration options. It then analyzes
 * the classpath, for example to identify the services to be loaded,
 * or to provide some alternative implementations to existing
 * classes.
 */
@Experimental
public final class MicronautAotOptimizer implements ConfigKeys {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicronautAotOptimizer.class);

    public static final String OUTPUT_RESOURCES_FILE_NAME = "resource-filter.txt";
    public static final String CUSTOMIZER_CLASS_NAME = "AOTApplicationContextCustomizer";

    private final List<File> classpath;
    private final File outputSourcesDirectory;
    private final File outputClassesDirectory;
    private final File logsDirectory;

    private MicronautAotOptimizer(List<File> classpath,
                                  File outputSourcesDirectory,
                                  File outputClassesDirectory,
                                  File logsDirectory) {
        this.classpath = classpath;
        this.outputSourcesDirectory = outputSourcesDirectory;
        this.outputClassesDirectory = outputClassesDirectory;
        this.logsDirectory = logsDirectory;
    }

    private void compileGeneratedSources(List<File> extraClasspath, List<JavaFile> javaFiles) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> ds = new DiagnosticCollector<>();
        try (StandardJavaFileManager mgr = compiler.getStandardFileManager(ds, null, null)) {
            List<File> fullClasspath = new ArrayList<>(classpath);
            fullClasspath.addAll(extraClasspath);
            List<String> options = compilerOptions(outputClassesDirectory, fullClasspath);
            List<File> filesToCompile = outputSourceFilesToSourceDir(outputSourcesDirectory, javaFiles);
            if (outputClassesDirectory.exists() || outputClassesDirectory.mkdirs()) {
                Iterable<? extends JavaFileObject> sources = mgr.getJavaFileObjectsFromFiles(filesToCompile);
                JavaCompiler.CompilationTask task = compiler.getTask(null, mgr, ds, options, null, sources);
                task.call();
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to compile generated classes", e);
        }
        List<Diagnostic<? extends JavaFileObject>> diagnostics = ds.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
        if (!diagnostics.isEmpty()) {
            throwCompilationError(diagnostics);
        }
    }

    private static String mandatoryValue(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isEmpty()) {
            invalidConfiguration(key, "should not be null or empty");
        }
        return value;
    }

    private static <T> T optionalValue(Properties config, String key, Function<Optional<String>, T> producer) {
        String value = config.getProperty(key);
        if (value == null) {
            Object raw = config.get(key);
            if (raw != null) {
                value = String.valueOf(raw);
            }
        }
        return producer.apply(Optional.ofNullable(value));
    }

    private static boolean booleanValue(Properties config, String key, boolean defaultValue) {
        return optionalValue(config, key, s -> s.map(Boolean::parseBoolean).orElse(defaultValue));
    }

    private static void invalidConfiguration(String key, String message) {
        throw new IllegalStateException("Parameter '" + "'" + key + " " + message);
    }

    private static List<String> splitToList(String value) {
        return Arrays.stream(value.split("[:,]\\s*"))
                .filter(s -> !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * This convenience method uses properties to load the configuration.
     * This is useful because the optimizer must be found on the same
     * classloader as the application under optimization, otherwise it
     * would mean that we could have a clash between Micronaut runtime
     * versions.
     *
     * @param config the configuration
     */
    public static void execute(Properties config) {
        String pkg = mandatoryValue(config, GENERATED_PACKAGE);
        File outputDir = new File(mandatoryValue(config, OUTPUT_DIRECTORY));
        File sourcesDir = new File(outputDir, "sources");
        File classesDir = new File(outputDir, "classes");
        File logsDir = new File(outputDir, "logs");
        runner(pkg, sourcesDir, classesDir, logsDir)
                .forRuntime(optionalValue(config, RUNTIME, v -> v.map(r -> Runtime.valueOf(r.toUpperCase())).orElse(Runtime.JIT)))
                .addClasspath(optionalValue(config, CLASSPATH, v -> v.map(MicronautAotOptimizer::splitToList).map(l -> l.stream().map(File::new).collect(Collectors.toList())).orElse(Collections.emptyList())))
                .sealEnvironment(booleanValue(config, SEALED_ENVIRONMENT, true))
                .preCheckRequirements(booleanValue(config, PRECHECK_BEAN_REQUIREMENTS, true))
                .replaceLogbackXml(booleanValue(config, REPLACE_LOGBACK, false))
                .preloadEnvironment(booleanValue(config, PRELOAD_ENVIRONMENT, true))
                .scanForReactiveTypes(booleanValue(config, SCAN_REACTIVE_TYPES, true))
                .checkMissingTypes(optionalValue(config, TYPES_TO_CHECK, v -> v.map(MicronautAotOptimizer::splitToList)).orElse(Collections.emptyList()))
                .scanForServiceClasses(optionalValue(config, SERVICE_TYPES, v -> v.map(MicronautAotOptimizer::splitToList)).orElse(Collections.emptyList()))
                .execute();
    }

    public static Runner runner(String generatedPackage,
                                File outputSourcesDirectory,
                                File outputClassesDirectory,
                                File logsDirectory) {
        return new Runner(generatedPackage, outputSourcesDirectory, outputClassesDirectory, logsDirectory);
    }

    private static List<File> outputSourceFilesToSourceDir(File srcDir, List<JavaFile> javaFiles) {
        List<File> srcFiles = new ArrayList<>(javaFiles.size());
        if (srcDir.isDirectory() || srcDir.mkdirs()) {
            StreamHelper.trying(() -> {
                for (JavaFile javaFile : javaFiles) {
                    javaFile.writeTo(srcDir);
                }
                Files.walkFileTree(srcDir.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        srcFiles.add(file.toFile());
                        return super.visitFile(file, attrs);
                    }
                });
            });
        }
        return srcFiles;
    }

    private static void throwCompilationError(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder sb = new StringBuilder("Compilation errors:\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            JavaFileObject source = d.getSource();
            String srcFile = source == null ? "unknown" : new File(source.toUri()).getName();
            String diagLine = String.format("File %s, line: %d, %s", srcFile, d.getLineNumber(), d.getMessage(null));
            sb.append(diagLine).append("\n");
        }
        throw new RuntimeException(sb.toString());
    }

    private static List<String> compilerOptions(File dstDir,
                                                List<File> classPath) {
        List<String> options = new ArrayList<>();
        options.add("-source");
        options.add("1.8");
        options.add("-target");
        options.add("1.8");
        options.add("-classpath");
        String cp = classPath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
        options.add(cp);
        options.add("-d");
        options.add(dstDir.getAbsolutePath());
        return options;
    }

    private void writeLogs(SourceGenerationContext context) {
        if (logsDirectory.isDirectory() || logsDirectory.mkdirs()) {
            writeLines(new File(logsDirectory, OUTPUT_RESOURCES_FILE_NAME), context.getExcludedResources());
            context.getDiagnostics().forEach((key, messages) -> {
                File logFile = new File(logsDirectory, key.toLowerCase(Locale.US) + ".log");
                writeLines(logFile, messages);
            });
        }
    }

    private static void writeLines(File outputFile, Collection<String> lines) {
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)
        )) {
            lines.forEach(writer::println);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main AOT optimizer runner.
     */
    public static final class Runner {
        private final List<File> classpath = new ArrayList<>();
        private final String generatedPackage;
        private final File outputSourcesDirectory;
        private final File outputClassesDirectory;
        private final File logsDirectory;

        private final List<String> classesToCheck = new ArrayList<>();
        private final List<String> serviceClasses = new ArrayList<>();

        private Runtime runtime = Runtime.JIT;
        private boolean sealEnvironment = true;
        private boolean preloadEnvironment = true;
        private boolean scanForReactiveTypes = true;
        private boolean replaceLogbackXml = true;
        private boolean precheckRequirements = true;

        public Runner(String generatedPackage,
                      File outputSourcesDirectory,
                      File outputClassesDirectory,
                      File logsDirectory
        ) {
            this.generatedPackage = generatedPackage;
            this.outputSourcesDirectory = outputSourcesDirectory;
            this.outputClassesDirectory = outputClassesDirectory;
            this.logsDirectory = logsDirectory;
        }

        public Runner forRuntime(Runtime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Runner preloadEnvironment(boolean preload) {
            this.preloadEnvironment = preload;
            return this;
        }

        public Runner scanForReactiveTypes(boolean scan) {
            this.scanForReactiveTypes = scan;
            return this;
        }

        public Runner preCheckRequirements(boolean test) {
            this.precheckRequirements = test;
            return this;
        }

        public Runner sealEnvironment(boolean cache) {
            this.sealEnvironment = cache;
            return this;
        }

        public Runner replaceLogbackXml(boolean replace) {
            this.replaceLogbackXml = replace;
            return this;
        }

        /**
         * Adds elements to the application classpath.
         *
         * @param elements the files to add to classpath
         * @return this builder
         */
        public Runner addClasspath(Collection<File> elements) {
            classpath.addAll(elements);
            return this;
        }

        /**
         * Registers a number of class names which we want to find on classpath.
         * If they are not, code will be optimized at runtime so that verification
         * is done at no cost.
         *
         * @param classNames a list of class names which are typically passed
         * to {@link io.micronaut.core.reflect.ClassUtils#forName(String, ClassLoader)}
         * @return this builder
         */
        public Runner checkMissingTypes(Collection<String> classNames) {
            classesToCheck.addAll(classNames);
            return this;
        }

        /**
         * Adds service classes to be searched for on classpath. Under the hood
         * it will use the service loader to scan for services and generate
         * classes which optimize their lookup.
         *
         * @param serviceClasses the list of service types to search for
         * @return this builder
         */
        public Runner scanForServiceClasses(Collection<String> serviceClasses) {
            this.serviceClasses.addAll(serviceClasses);
            return this;
        }

        @SuppressWarnings("unchecked")
        public Runner execute() {
            MicronautAotOptimizer optimizer = new MicronautAotOptimizer(
                    classpath,
                    outputSourcesDirectory,
                    outputClassesDirectory,
                    logsDirectory);
            ApplicationContextAnalyzer analyzer = ApplicationContextAnalyzer.create();
            Set<String> environmentNames = analyzer.getEnvironmentNames();
            LOGGER.info("Detected environments: {}", environmentNames);
            Predicate<AnnotationMetadataProvider> beanFilter = precheckRequirements ? analyzer.getAnnotationMetadataPredicate() : p -> true;
            SourceGenerationContext context = new SourceGenerationContext(generatedPackage);
            List<SourceGenerator> sourceGenerators = new ArrayList<>();
            if (!classesToCheck.isEmpty()) {
                sourceGenerators.add(new KnownMissingTypesSourceGenerator(context, classesToCheck));
            }
            AbstractStaticServiceLoaderSourceGenerator serviceLoaderGenerator = null;
            if (!serviceClasses.isEmpty()) {
                Set<String> resourceNames = new LinkedHashSet<>();
                resourceNames.add("application");
                environmentNames.stream()
                        .map(env -> "application-" + env)
                        .forEach(resourceNames::add);
                Map<String, AbstractSourceGenerator> substitutions = Collections.singletonMap(YamlPropertySourceLoader.class.getName(), new YamlPropertySourceGenerator(context, resourceNames));
                if (runtime == Runtime.JIT) {
                    serviceLoaderGenerator = new JitStaticServiceLoaderSourceGenerator(
                            context,
                            beanFilter,
                            serviceClasses,
                            n -> false,
                            substitutions
                    );
                } else {
                    serviceLoaderGenerator = new NativeStaticServiceLoaderSourceGenerator(
                            context,
                            beanFilter,
                            serviceClasses,
                            n -> false,
                            substitutions
                    );
                }
                sourceGenerators.add(serviceLoaderGenerator);
            }
            if (preloadEnvironment) {
                sourceGenerators.add(new EnvironmentPropertiesSourceGenerator(context));
            }
            if (scanForReactiveTypes) {
                sourceGenerators.add(new PublishersSourceGenerator(context));
            }
            if (!serviceClasses.isEmpty()) {
                sourceGenerators.add(new ConstantPropertySourcesSourceGenerator(context, serviceLoaderGenerator));
            }
            if (sealEnvironment) {
                sourceGenerators.add(new EnableCachedEnvironmentSourceGenerator(context));
            }
            if (replaceLogbackXml) {
                sourceGenerators.add(new LogbackConfigurationSourceGenerator(context));
            }
            if (runtime == Runtime.NATIVE) {
                sourceGenerators.add(new GraalVMOptimizationFeatureSourceGenerator(context, CUSTOMIZER_CLASS_NAME, serviceClasses));
            }
            ApplicationContextCustomizerGenerator generator = new ApplicationContextCustomizerGenerator(
                    context,
                    CUSTOMIZER_CLASS_NAME,
                    sourceGenerators
            );
            generator.init();
            optimizer.compileGeneratedSources(context.getExtraClasspath(), generator.generateSourceFiles());
            generator.generateResourceFiles(outputClassesDirectory);
            optimizer.writeLogs(context);
            return this;
        }
    }

    /**
     * The targetted type of runtime.
     */
    public enum Runtime {
        JIT,
        NATIVE
    }

}
