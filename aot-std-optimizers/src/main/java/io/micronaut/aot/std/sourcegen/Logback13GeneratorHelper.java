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
package io.micronaut.aot.std.sourcegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.model.ConfigurationModel;
import ch.qos.logback.classic.model.LoggerModel;
import ch.qos.logback.classic.model.RootLoggerModel;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.joran.util.beans.BeanDescription;
import ch.qos.logback.core.joran.util.beans.BeanDescriptionCache;
import ch.qos.logback.core.model.AppenderModel;
import ch.qos.logback.core.model.AppenderRefModel;
import ch.qos.logback.core.model.ComponentModel;
import ch.qos.logback.core.model.ImplicitModel;
import ch.qos.logback.core.model.Model;
import ch.qos.logback.core.model.NamedComponentModel;
import ch.qos.logback.core.spi.ContextAware;
import ch.qos.logback.core.spi.LifeCycle;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.aot.core.AOTContext;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static ch.qos.logback.classic.Level.toLevel;

class Logback13GeneratorHelper {
    static MethodSpec configureMethod(String fileName, AOTContext aotContext) {
        JoranConfigurator joranConfigurator = new JoranConfigurator();
        LoggerContext context = new LoggerContext();
        joranConfigurator.setContext(context);
        try {
            URL logbackFile = aotContext.getAnalyzer().getApplicationContext().getClass().getClassLoader().getResource(fileName);
            if (logbackFile == null) {
                throw new IllegalStateException("Could not find " + fileName + " file on application classpath");
            }
            joranConfigurator.doConfigure(logbackFile);
        } catch (JoranException e) {
            throw new RuntimeException(e);
        }
        Model model = joranConfigurator.getInterpretationContext().peekModel();
        CodeBlock.Builder codeBuilder = CodeBlock.builder();
        BeanDescriptionCache beanDescriptionCache = new BeanDescriptionCache(context);
        ModelVisitor visitor = new ModelVisitor() {
            private final Map<String, Set<String>> loggerToAppenders = new HashMap<>();
            private final Map<String, String> appenderRefToAppenderVarName = new HashMap<>();
            private final Map<Model, String> modelToVarName = new HashMap<>();

            private String varNameOf(Model model) {
                return modelToVarName.computeIfAbsent(model, m -> {
                    String var = toVarName(model);
                    if (modelToVarName.containsValue(var)) {
                        var = toVarName(model) + "_" + modelToVarName.size();
                    }
                    return var;
                });
            }

            private String toVarName(Model model) {
                String name;
                if (model instanceof NamedComponentModel) {
                    name = ((NamedComponentModel) model).getName();
                } else if (model instanceof LoggerModel) {
                    name = ((LoggerModel) model).getName();
                } else {
                    name = model.getTag();
                }
                return name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase(Locale.US);
            }

            @Override
            public void visitRootLogger(RootLoggerModel model, Model parent) {
                codeBuilder.addStatement("$T _rootLogger = loggerContext.getLogger($T.ROOT_LOGGER_NAME)", ch.qos.logback.classic.Logger.class, ch.qos.logback.classic.Logger.class);
                String level = model.getLevel();
                if (level != null) {
                    codeBuilder.addStatement("_rootLogger.setLevel($T.$L)", ClassName.get(Level.class), toLevel(level));
                }
                collectAppenders(model, "_rootLogger");
            }

            @Override
            public void visitLogger(LoggerModel model, Model parent) {
                String loggerVarName = varNameOf(model);
                codeBuilder.addStatement("$T $L = loggerContext.getLogger($S)", ch.qos.logback.classic.Logger.class, loggerVarName, model.getName());
                String level = model.getLevel();
                if (level != null) {
                    codeBuilder.addStatement("$L.setLevel($T.$L)", loggerVarName, ClassName.get(Level.class), toLevel(level));
                }
                String additivity = model.getAdditivity();
                if (additivity != null) {
                    codeBuilder.addStatement("$L.setAdditive($L)", loggerVarName, Boolean.valueOf(additivity));
                }
                collectAppenders(model, loggerVarName);
            }

            private void collectAppenders(Model model, String loggerVarName) {
                Set<String> appenders = model.getSubModels().stream()
                        .filter(AppenderRefModel.class::isInstance)
                        .map(AppenderRefModel.class::cast)
                        .map(AppenderRefModel::getRef)
                        .collect(Collectors.toSet());
                loggerToAppenders.put(loggerVarName, appenders);
            }

            @Override
            public void visitAppender(AppenderModel model, Model parent) {
                ClassName appenderName = ClassName.bestGuess(model.getClassName());
                String varName = varNameOf(model);
                appenderRefToAppenderVarName.put(model.getName(), varName);
                codeBuilder.addStatement("$T $L = new $T()", appenderName, varName, appenderName);
            }

            @Override
            public void visitImplicit(ImplicitModel model, Model parent) {
                String className = model.getClassName();
                if (className == null && parent instanceof ComponentModel) {
                    generateSetterCode(model, parent);
                } else if (className != null) {
                    String varName = varNameOf(model);
                    ClassName elementType = ClassName.bestGuess(className);
                    codeBuilder.addStatement("$T $L = new $T()", elementType, varName, elementType);
                }
            }

            @Override
            public void postVisitImplicit(ImplicitModel model, Model parent) {
                String className = model.getClassName();
                if (className != null) {
                    generateSetterCode(model, parent);
                }
            }

            @Override
            public void postVisit(Model model, Model parent) {
                if (model instanceof ComponentModel) {
                    String className = ((ComponentModel) model).getClassName();
                    if (className != null) {
                        try {
                            Class<?> clazz = Class.forName(className);
                            if (ContextAware.class.isAssignableFrom(clazz)) {
                                codeBuilder.addStatement("$L.setContext(context)", varNameOf(model));
                            }
                            if (LifeCycle.class.isAssignableFrom(clazz)) {
                                codeBuilder.addStatement("$L.start()", varNameOf(model));
                            }
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                ModelVisitor.super.postVisit(model, parent);
            }

            private void generateSetterCode(ImplicitModel model, Model parent) {
                if (!maybeGenerateAddOrSet(model, parent, BeanDescription::getSetter)) {
                    maybeGenerateAddOrSet(model, parent, BeanDescription::getAdder);
                }
            }

            private boolean maybeGenerateAddOrSet(ImplicitModel model, Model parent, BiFunction<BeanDescription, String, Method> methodFinder) {
                try {
                    String ownerClassName = ((ComponentModel) parent).getClassName();
                    BeanDescription beanDescription = beanDescriptionCache.getBeanDescription(Class.forName(ownerClassName));
                    Method method = methodFinder.apply(beanDescription, model.getTag());
                    if (method != null) {
                        Class<?> parameterType = method.getParameterTypes()[0];
                        String parentVarName = varNameOf(parent);
                        if (model.getBodyText() == null) {
                            codeBuilder.addStatement("$L.$L($L)", parentVarName, method.getName(), varNameOf(model));
                            return true;
                        } else if (parameterType.isPrimitive()) {
                            Object value = toPrimitiveValue(model, parameterType);
                            codeBuilder.addStatement("$L.$L($L)", parentVarName, method.getName(), value);
                            return true;
                        } else if (String.class.equals(parameterType)) {
                            codeBuilder.addStatement("$L.$L($S)", parentVarName, method.getName(), model.getBodyText());
                            return true;
                        } else {
                            try {
                                Method valueOf = parameterType.getDeclaredMethod("valueOf", String.class);
                                codeBuilder.addStatement("$L.$L($T.valueOf($S))", parentVarName, method.getName(), ClassName.get(parameterType), model.getBodyText());
                                return true;
                            } catch (NoSuchMethodException e) {
                                throw new RuntimeException("Unable to convert type" + parameterType);
                            }
                        }
                    }
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                return false;
            }

            private Object toPrimitiveValue(ImplicitModel model, Class<?> parameterType) {
                Object value;
                String bodyText = model.getBodyText();
                if (parameterType.equals(boolean.class)) {
                    value = Boolean.valueOf(bodyText);
                } else if (parameterType.equals(byte.class)) {
                    value = Byte.valueOf(bodyText);
                } else if (parameterType.equals(char.class)) {
                    value = bodyText.charAt(0);
                } else if (parameterType.equals(double.class)) {
                    value = Double.valueOf(bodyText);
                } else if (parameterType.equals(float.class)) {
                    value = Float.valueOf(bodyText);
                } else if (parameterType.equals(int.class)) {
                    value = Integer.valueOf(bodyText);
                } else if (parameterType.equals(long.class)) {
                    value = Long.valueOf(bodyText);
                } else if (parameterType.equals(short.class)) {
                    value = Short.valueOf(bodyText);
                } else {
                    value = bodyText;
                }
                return value;
            }

            @Override
            public void postVisitConfiguration(ConfigurationModel model, Model parent) {
                for (Map.Entry<String, Set<String>> entry : loggerToAppenders.entrySet()) {
                    String loggerName = entry.getKey();
                    for (String appenderRef : entry.getValue()) {
                        String varName = appenderRefToAppenderVarName.get(appenderRef);
                        if (varName != null) {
                            codeBuilder.addStatement("$L.addAppender($L)", loggerName, varName);
                        }
                    }
                }
            }
        };
        visitor.visit(model);

        return MethodSpec.methodBuilder("configure")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(LoggerContext.class, "loggerContext")
                .addCode(codeBuilder.build())
                .build();
    }

    interface ModelVisitor {

        default void visit(Model model) {
            visit(model, null);
        }

        default void visit(Model model, Model parent) {
            previsit(model, parent);
            model.getSubModels().forEach(m -> visit(m, model));
            postVisit(model, parent);
        }

        default void previsit(Model model, Model parent) {
            if (model instanceof RootLoggerModel) {
                visitRootLogger((RootLoggerModel) model, parent);
            }
            if (model instanceof AppenderModel) {
                visitAppender((AppenderModel) model, parent);
            }
            if (model instanceof ImplicitModel) {
                visitImplicit((ImplicitModel) model, parent);
            }
            if (model instanceof LoggerModel) {
                visitLogger((LoggerModel) model, parent);
            }
            if (model instanceof ConfigurationModel) {
                visitConfiguration((ConfigurationModel) model, parent);
            }
        }

        default void visitConfiguration(ConfigurationModel model, Model parent) {

        }

        default void postVisitConfiguration(ConfigurationModel model, Model parent) {

        }

        default void postVisit(Model model, Model parent) {
            if (model instanceof RootLoggerModel) {
                postVisitRootLogger((RootLoggerModel) model, parent);
            }
            if (model instanceof AppenderModel) {
                postVisitAppender((AppenderModel) model, parent);
            }
            if (model instanceof ImplicitModel) {
                postVisitImplicit((ImplicitModel) model, parent);
            }
            if (model instanceof LoggerModel) {
                postVisitLogger((LoggerModel) model, parent);
            }
            if (model instanceof ConfigurationModel) {
                postVisitConfiguration((ConfigurationModel) model, parent);
            }
        }

        default void visitRootLogger(RootLoggerModel model, Model parent) {
        }

        default void postVisitRootLogger(RootLoggerModel model, Model parent) {
        }

        default void visitLogger(LoggerModel model, Model parent) {
        }

        default void postVisitLogger(LoggerModel model, Model parent) {
        }

        default void visitAppender(AppenderModel model, Model parent) {

        }

        default void postVisitAppender(AppenderModel model, Model parent) {

        }

        default void visitImplicit(ImplicitModel model, Model parent) {

        }

        default void postVisitImplicit(ImplicitModel model, Model parent) {

        }
    }
}
