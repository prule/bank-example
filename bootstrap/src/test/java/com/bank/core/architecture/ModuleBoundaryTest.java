package com.bank.core.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.core");
    }

    @Test
    void domainHasNoFrameworkDependencies() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.core.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "org.openapitools..",
                        "com.bank.core.dto..",
                        "com.bank.core.api.."
                )
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void applicationHasNoFrameworkDependencies() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.core.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.bank.core.infrastructure..",
                        "com.bank.core.dto..",
                        "com.bank.core.api.."
                )
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void domainAndApplicationDoNotImportInfrastructureOrConfig() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.bank.core.domain..", "com.bank.core.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bank.core.infrastructure..", "com.bank.core.config..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void jpaEntitiesLiveInInfrastructurePersistence() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("com.bank.core.infrastructure.persistence..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void noClassesOutsideConcurrencyAdapterMayUseReentrantLock() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.bank.core.infrastructure.concurrency..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.locks.ReentrantLock")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void noClassesOutsideConcurrencyAdapterMayUseTransactionSynchronizationManager() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.bank.core.infrastructure.concurrency..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.transaction.support.TransactionSynchronizationManager")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
