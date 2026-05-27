package com.bank.core.bootstrap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

public class ArchUnitBoundaryTest {

    private final JavaClasses javaClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bank.core");

    @Test
    public void testDomainModuleBoundaries() {
        ArchRule rule = noClasses().that().resideInAPackage("com.bank.core.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "org.openapitools..",
                        "com.bank.core.infrastructure..",
                        "com.bank.core.config..",
                        "com.bank.core.dto..",
                        "com.bank.core.api.."
                );
        rule.check(javaClasses);
    }

    @Test
    public void testApplicationModuleBoundaries() {
        ArchRule rule = noClasses().that().resideInAPackage("com.bank.core.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "org.openapitools..",
                        "com.bank.core.infrastructure..",
                        "com.bank.core.config..",
                        "com.bank.core.dto..",
                        "com.bank.core.api.."
                );
        rule.check(javaClasses);
    }

    @Test
    public void testJpaEntitiesConfinedToInfrastructure() {
        ArchRule rule = classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("com.bank.core.infrastructure.persistence..");
        rule.check(javaClasses);
    }
}
