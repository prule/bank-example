package com.bank.core.scheduling;

import com.bank.core.application.ledger.SweepReport;
import com.bank.core.application.ledger.VerifyPendingJournals;
import com.bank.core.infrastructure.scheduling.JournalVerificationProperties;
import com.bank.core.infrastructure.scheduling.JournalVerificationScheduler;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class JournalVerificationArchUnitTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.core");
    }

    @Test
    void schedulerLivesInInfrastructureSchedulingPackage() {
        ArchRule rule = classes()
                .that().haveSimpleName(JournalVerificationScheduler.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.infrastructure.scheduling..");
        rule.check(classes);
    }

    @Test
    void propertiesLiveInInfrastructureSchedulingPackage() {
        ArchRule rule = classes()
                .that().haveSimpleName(JournalVerificationProperties.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.infrastructure.scheduling..");
        rule.check(classes);
    }

    @Test
    void useCaseAndReportLiveInApplicationLedgerPackage() {
        ArchRule useCase = classes()
                .that().haveSimpleName(VerifyPendingJournals.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.application.ledger..");
        useCase.check(classes);

        ArchRule report = classes()
                .that().haveSimpleName(SweepReport.class.getSimpleName())
                .should().resideInAPackage("com.bank.core.application.ledger..");
        report.check(classes);
    }
}
