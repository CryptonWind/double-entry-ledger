package com.ledger.unit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/** Bytecode-level checks include imports, method calls, field types, annotations and signatures. No Spring context. */
class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.ledger");

    @Test
    void domainDependsOnlyOnItselfAndJava() {
        classes().that().resideInAPackage("com.ledger.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("com.ledger.domain..", "java..", "lombok..")
                .check(CLASSES);
    }

    @Test
    void applicationNeverDependsOnInfrastructureOrFrameworkImplementations() {
        noClasses().that().resideInAPackage("com.ledger.application..")
                .should().dependOnClassesThat().resideInAnyPackage("com.ledger.infrastructure..", "jakarta..",
                        "org.springframework..", "org.hibernate..", "com.rabbitmq..", "tools.jackson..")
                .check(CLASSES);
    }

    @Test
    void persistenceEntitiesAreConfinedToOutputPersistence() {
        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage("com.ledger.infrastructure.output.persistence..")
                .check(CLASSES);
        noClasses().that().resideOutsideOfPackage("com.ledger.infrastructure..")
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .check(CLASSES);
    }

    @Test
    void controllersDoNotCallConcreteApplicationServicesOrPersistence() {
        noClasses().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().accessClassesThat().haveSimpleNameEndingWith("Service")
                .check(CLASSES);
        noClasses().that().resideInAPackage("com.ledger.infrastructure.input.rest..")
                .should().dependOnClassesThat().resideInAnyPackage("com.ledger.infrastructure.output.persistence..",
                        "jakarta.persistence..", "org.springframework.data..")
                .check(CLASSES);
    }
}
