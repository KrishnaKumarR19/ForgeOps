package com.forgeops.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces modular-monolith boundaries (ARCHITECTURE.md §2, DOMAIN_MODEL.md §1) so they
 * are executable, not merely documentary. These rules must hold even as modules gain
 * real code in later phases.
 *
 * <p>Rules encoded here:
 * <ul>
 *   <li>No cyclic dependencies between {@code com.forgeops.*} modules.</li>
 *   <li>The platform never depends on the optional {@code ai} module (ADR-0012/0015).</li>
 *   <li>Authoritative modules (identity, events, incidents, audit) do not depend on
 *       supporting capabilities (notifications, analytics, ai).</li>
 * </ul>
 *
 * <p>In Phase 2 the module packages are essentially empty; these rules pass trivially now
 * and become meaningful guards as code is added.
 */
class ModuleBoundaryTests {

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.forgeops");

    /** Treats each direct sub-package of com.forgeops as a module "slice". */
    private static final SliceAssignment MODULES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(com.tngtech.archunit.core.domain.JavaClass javaClass) {
            String pkg = javaClass.getPackageName();
            String base = "com.forgeops.";
            if (pkg.startsWith(base) && pkg.length() > base.length()) {
                String rest = pkg.substring(base.length());
                String module = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
                return SliceIdentifier.of(module);
            }
            return SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
            return "ForgeOps modules";
        }
    };

    @Test
    void modulesAreFreeOfCycles() {
        SliceRule rule = slices().assignedFrom(MODULES).should().beFreeOfCycles();
        rule.check(CLASSES);
    }

    @Test
    void platformDoesNotDependOnOptionalAiModule() {
        Architectures.LayeredArchitecture rule = Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("ai").definedBy("com.forgeops.ai..")
                .layer("platform").definedBy(
                        "com.forgeops.identity..",
                        "com.forgeops.events..",
                        "com.forgeops.incidents..",
                        "com.forgeops.audit..",
                        "com.forgeops.notifications..",
                        "com.forgeops.analytics..")
                .whereLayer("ai").mayNotBeAccessedByAnyLayer();
        rule.check(CLASSES);
    }

    @Test
    void authoritativeModulesDoNotDependOnSupportingCapabilities() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.forgeops.identity..",
                        "com.forgeops.events..",
                        "com.forgeops.incidents..",
                        "com.forgeops.audit..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.forgeops.notifications..",
                        "com.forgeops.analytics..",
                        "com.forgeops.ai..")
                .check(CLASSES);
    }

    @Test
    void identityInfrastructureIsNotAccessedByOtherModules() {
        // A module's infrastructure (persistence adapters, JPA entities) is internal and
        // must not be referenced from outside that module (ADR-0030). Checked for identity
        // now that it has real infrastructure code.
        noClasses()
                .that().resideOutsideOfPackage("com.forgeops.identity..")
                .should().dependOnClassesThat().resideInAPackage("com.forgeops.identity.infrastructure..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
