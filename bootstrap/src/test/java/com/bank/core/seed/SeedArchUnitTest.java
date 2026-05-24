package com.bank.core.seed;

import com.bank.core.infrastructure.seed.SeedDataRunner;
import com.bank.core.infrastructure.seed.SeedProperties;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class SeedArchUnitTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.core");
    }

    @Test
    void seedRunnerLivesInInfrastructureSeedPackage() {
        ArchRule rule = classes()
                .that().haveSimpleName(SeedDataRunner.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.infrastructure.seed..");
        rule.check(classes);
    }

    @Test
    void seedPropertiesLiveInInfrastructureSeedPackage() {
        ArchRule rule = classes()
                .that().haveSimpleName(SeedProperties.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.infrastructure.seed..");
        rule.check(classes);
    }
}
