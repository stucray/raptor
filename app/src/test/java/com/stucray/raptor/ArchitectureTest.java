package com.stucray.raptor;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * Architectural rules enforced at test time. Add project-specific rules below;
 * if a rule is disabled, document the carve-out reason on @Disabled.
 *
 * No noCycles slice here — Modulith's ApplicationModules.verify() (see
 * ApplicationModuleArchitectureTest) already enforces top-level cycle freedom.
 */
@DisplayName("Architectural rules hold")
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(DO_NOT_INCLUDE_TESTS)
        // com.stucray, not com.stucray.raptor: the absorbed acquisition module
        // is on this module's classpath and gains these rules rather than a
        // carve-out (PRD #73).
        .importPackages("com.stucray");

    @Test
    @DisplayName("@Component classes are package-private")
    void componentsArePackagePrivate() {
        classes().that().areMetaAnnotatedWith(Component.class)
            .and().areNotMetaAnnotatedWith(Configuration.class)
            .should().notBePublic()
            .allowEmptyShould(true)
            .check(CLASSES);
    }

    @Test
    @DisplayName("@Configuration classes are package-private (except AutoConfigurations)")
    void configsArePackagePrivate() {
        Set<String> autoConfigs = readAutoConfigurationImports();
        classes().that().areAnnotatedWith(Configuration.class)
            .and(notIn(autoConfigs))
            .should().notBePublic()
            .allowEmptyShould(true)
            .check(CLASSES);
    }

    /**
     * The inverse of what this rule used to say. It banned
     * {@code @EnableBatchProcessing} outright, on the belief that the annotation
     * would back Boot's auto-configuration off and leave a resourceless job
     * repository — when Boot's auto-configuration is itself what produces one
     * (#84). The annotation is now required, in exactly one place, to get a JDBC
     * repository. Confining it there is still worth enforcing: a second one
     * elsewhere would be a competing batch configuration.
     */
    @Test
    @DisplayName("@EnableBatchProcessing appears once, on the JDBC job repository configuration")
    void enableBatchProcessingIsConfinedToOneClass() {
        classes().that().areAnnotatedWith(EnableBatchProcessing.class)
            .should().haveFullyQualifiedName(
                "com.stucray.raptor.batch.BatchJobRepositoryConfiguration")
            .because("the annotation exists to replace Boot's resourceless job repository "
                + "with a JDBC-backed one; a second batch configuration would compete "
                + "with it")
            .check(CLASSES);
    }

    private static Set<String> readAutoConfigurationImports() {
        Path imports = Paths.get("src/main/resources/META-INF/spring/"
            + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        if (!Files.exists(imports)) return Set.of();
        try (Stream<String> lines = Files.lines(imports)) {
            return lines.map(String::trim)
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .collect(toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DescribedPredicate<JavaClass> notIn(Set<String> excluded) {
        return new DescribedPredicate<>("not listed in AutoConfiguration.imports") {
            @Override
            public boolean test(JavaClass c) {
                return !excluded.contains(c.getName());
            }
        };
    }
}
