package com.blackducksoftware.integration.hub.docker;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import com.blackducksoftware.integration.hub.docker.client.ClassPathPropertiesFile;
import com.blackducksoftware.integration.hub.docker.help.ValueDescription;

@Component
public class Config {
    private final static String GROUP_PUBLIC = "public";
    private final static String GROUP_PRIVATE = "private";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    ConfigurableEnvironment configurableEnvironment;

    private boolean initialized = false;
    private final SortedSet<String> sortedPropNames = new TreeSet<>();

    public void init() throws IOException {
        if (initialized) {
            return;
        }
        final ClassPathPropertiesFile props = new ClassPathPropertiesFile("application.properties");
        for (final Object propObj : props.keySet()) {
            if (propObj instanceof String) {
                final String propName = (String) propObj;
                sortedPropNames.add(propName);
            }
        }
        for (final String propName : sortedPropNames) {
            logger.info(String.format("*** Found property name: %s, value: %s", propName, props.getProperty(propName)));
        }
        initialized = true;
    }

    public SortedSet<String> getSortedPropNames() throws IOException {
        init();
        return sortedPropNames;
    }

    @ValueDescription(description = "This is test.prop.public.string's description", defaultValue = "someDefault", group = Config.GROUP_PUBLIC)
    @Value("${test.prop.public.string:}")
    String testPropPublicString;

    @ValueDescription(description = "This is test.prop.public.boolean's description", defaultValue = "false", group = Config.GROUP_PUBLIC)
    @Value("${test.prop.public.boolean:false}")
    Boolean testPropPublicBoolean;

    @ValueDescription(description = "This is test.prop.private's description", defaultValue = "false", group = Config.GROUP_PRIVATE)
    @Value("${test.prop.private:}")
    Boolean testPropPrivate;

    public String getTestPropPublicString() {
        return testPropPublicString;
    }

    public Boolean getTestPropPublicBoolean() {
        return testPropPublicBoolean;
    }

    // TODO not sure this belongs here
    public List<DockerInspectorOption> getConfigOptions() throws IllegalArgumentException, IllegalAccessException {
        final Object configObject = this;
        final List<DockerInspectorOption> opts = new ArrayList<>();
        for (final Field field : configObject.getClass().getDeclaredFields()) {
            final Annotation[] declaredAnnotations = field.getDeclaredAnnotations();
            if (declaredAnnotations.length > 0) {
                for (final Annotation annotation : declaredAnnotations) {
                    logger.debug(String.format("config object field: %s, annotation: %s", field.getName(), annotation.annotationType().getName()));
                    if (annotation.annotationType().getName().equals(ValueDescription.class.getName())) {
                        final String propMappingString = field.getAnnotation(Value.class).value();
                        final String propName = SpringValueUtils.springKeyFromValueAnnotation(propMappingString);
                        final ValueDescription valueDescription = field.getAnnotation(ValueDescription.class);
                        if (!Config.GROUP_PRIVATE.equals(valueDescription.group())) {
                            final Object fieldValueObject = field.get(configObject);
                            if (fieldValueObject == null) {
                                logger.warn(String.format("propName %s field is null", propName));
                                continue;
                            }
                            final String value = fieldValueObject.toString();
                            final DockerInspectorOption opt = new DockerInspectorOption(propName, field.getName(), value, valueDescription.description(), field.getType(), valueDescription.defaultValue(), valueDescription.group());
                            opts.add(opt);
                        } else {
                            logger.info(String.format("=== SKIPPING private prop: propName: %s, fieldName: %s, group: %s, description: %s", propName, field.getName(), valueDescription.group(), valueDescription.description()));
                        }
                    }
                }

            }
        }
        return opts;
    }

}
