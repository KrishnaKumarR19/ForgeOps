package com.forgeops.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module-internal layered architecture and dependency direction from
 * ADR-0030: within any module, {@code api → application → domain}, and
 * {@code infrastructure → application/domain}. The {@code domain} layer must remain
 * framework-independent.
 *
 * <p>These rules use package suffixes ({@code ..api..}, {@code ..domain..}, etc.), so they
 * apply to every module without naming each one, and become meaningful automatically as
 * modules gain layered code. With no layered code yet, they pass vacuously — by design,
 * not by brittleness.
 */
class LayeredArchitectureTests {

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.forgeops");

    @Test
    void domainDoesNotDependOnApiOrInfrastructure() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void domainDoesNotDependOnApplication() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void applicationDoesNotDependOnApi() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..api..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void domainIsFrameworkIndependent() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.boot..",
                        "jakarta.servlet..",
                        "jakarta.persistence..",
                        "org.springframework.data..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
