package com.bank.core.audit;

import com.bank.core.application.audit.AuditCheckpoints;
import com.bank.core.application.audit.DetectBalanceDrift;
import com.bank.core.application.audit.DriftReport;
import com.bank.core.application.audit.LedgerMovements;
import com.bank.core.infrastructure.audit.BalanceDriftAudit;
import com.bank.core.infrastructure.scheduling.BalanceDriftProperties;
import com.bank.core.infrastructure.scheduling.BalanceDriftScheduler;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class BalanceDriftArchUnitTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.core");
    }

    @Test
    void useCaseAndPortsLiveInApplicationAuditPackage() {
        for (Class<?> c : new Class<?>[]{DetectBalanceDrift.class, DriftReport.class,
                LedgerMovements.class, AuditCheckpoints.class}) {
            ArchRule rule = classes()
                    .that().haveSimpleName(c.getSimpleName())
                    .should().resideInAPackage("com.bank.core.application.audit..");
            rule.check(classes);
        }
    }

    @Test
    void facadeLivesInInfrastructureAuditPackage() {
        ArchRule rule = classes()
                .that().haveSimpleName(BalanceDriftAudit.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.infrastructure.audit..");
        rule.check(classes);
    }

    @Test
    void schedulerAndPropertiesLiveInInfrastructureSchedulingPackage() {
        for (Class<?> c : new Class<?>[]{BalanceDriftScheduler.class, BalanceDriftProperties.class}) {
            ArchRule rule = classes()
                    .that().haveSimpleName(c.getSimpleName())
                    .should().resideInAPackage("com.bank.core.infrastructure.scheduling..");
            rule.check(classes);
        }
    }

    @Test
    void jpaEntitiesAndAdaptersLiveInInfrastructurePersistencePackage() {
        // Match the persistence-layer artefacts (Entity, Repository, JpaAdapter) by
        // name suffix without sweeping in the application-layer port interfaces
        // (AuditCheckpoints, LedgerMovements) that legitimately live in com.bank.core.application.audit.
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Entity")
                .or().haveSimpleNameEndingWith("Repository")
                .or().haveSimpleNameEndingWith("JpaAdapter")
                .should().resideInAPackage("com.bank.core.infrastructure.persistence..");
        rule.check(classes);
    }
}
