package org.urizo.axmodulestudio.backend.coding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;

/**
 * A bean with two constructors and no {@code @Autowired} does not fail a unit test: the test
 * calls whichever constructor it wants. It fails the whole application at startup, because
 * Spring stops guessing and looks for a no-arg constructor that is not there.
 *
 * <p>That is how {@code CodingJobIntakeService} took down a local full run on 2026-09-02 after
 * 494 unit tests had passed. This walks the compiled coding classes and refuses the shape.
 *
 * <p>Spring's own component scanner cannot be used here: it evaluates {@code @Conditional},
 * and every bean in this package is switched off by default, so the scan came back empty and
 * the check passed without inspecting anything.
 */
class CodingBeanConstructorTest {

    private static final String PACKAGE_PATH = "org/urizo/axmodulestudio/backend/coding";

    @Test
    void everyCodingBeanTellsSpringWhichConstructorToUse() throws IOException {
        List<Class<?>> beans = codingBeans();
        assertThat(beans).as("coding beans found on the classpath").isNotEmpty();

        List<String> ambiguous = new ArrayList<>();
        for (Class<?> type : beans) {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                continue;
            }
            if (!hasNoArgument(constructors) && annotatedCount(constructors) != 1) {
                ambiguous.add(type.getName() + " declares " + constructors.length
                        + " constructors and none is annotated @Autowired,"
                        + " so Spring will look for a no-arg constructor and fail at startup");
            }
        }

        assertThat(ambiguous).isEmpty();
    }

    private static List<Class<?>> codingBeans() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> beans = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:" + PACKAGE_PATH + "/**/*.class")) {
            String className = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type = load(className);
            // @Service and @RestController are themselves annotated @Component.
            if (AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                beans.add(type);
            }
        }
        return beans;
    }

    private static boolean hasNoArgument(Constructor<?>[] constructors) {
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static int annotatedCount(Constructor<?>[] constructors) {
        int annotated = 0;
        for (Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                annotated++;
            }
        }
        return annotated;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, CodingBeanConstructorTest.class.getClassLoader());
        }
        catch (ClassNotFoundException | LinkageError notLoadable) {
            throw new AssertionError("A compiled coding class is not loadable: " + className, notLoadable);
        }
    }
}
