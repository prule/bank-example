package com.bank.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class NoApiDelegateTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.core");
    }

    @Test
    void noGeneratedApiDelegateOnProductionClasspath() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("ApiDelegate")
                .should().resideInAnyPackage("com.bank.core..")
                .as("the OpenAPI generator must run with interfaceOnly=true and delegatePattern=false; "
                        + "no *ApiDelegate types should appear on the production classpath")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
