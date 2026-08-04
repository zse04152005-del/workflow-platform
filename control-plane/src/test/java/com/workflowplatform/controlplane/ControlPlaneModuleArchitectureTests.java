package com.workflowplatform.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.workflowplatform.controlplane.definition.internal.DefinitionRepository;
import com.workflowplatform.controlplane.runtime.RuntimePublicProbe;
import com.workflowplatform.controlplane.runtime.RuntimeToDefinitionRepositoryViolation;
import com.workflowplatform.controlplane.runtime.internal.RuntimeInternalProbe;
import com.workflowplatform.controlplane.tenant.TenantToRuntimeInternalViolation;
import com.workflowplatform.controlplane.tenant.TenantToRuntimePublicViolation;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ControlPlaneModuleArchitectureTests {

    private static final String ROOT_PACKAGE = "com.workflowplatform.controlplane";

    private static final Set<String> EXPECTED_MODULES =
            Set.of(
                    "tenant",
                    "definition",
                    "runtime",
                    "scheduler",
                    "human-task",
                    "security",
                    "audit");

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT_PACKAGE);

    private static final Map<String, ModuleDeclaration> MODULES = discoverModules();

    @Test
    void declaresExactlyTheExpectedModulesAndValidDependencies() {
        assertEquals(
                EXPECTED_MODULES,
                MODULES.keySet(),
                "The production module set must match the architecture baseline");
        assertEquals(
                MODULES.size(),
                MODULES.values().stream().map(ModuleDeclaration::basePackage).distinct().count(),
                "Each module must own one unique root package");

        MODULES.values()
                .forEach(
                        module -> {
                            assertFalse(
                                    module.allowedDependencies().contains(module.name()),
                                    () -> module.name() + " must not depend on itself");
                            assertTrue(
                                    EXPECTED_MODULES.containsAll(module.allowedDependencies()),
                                    () -> module.name() + " declares an unknown module dependency");
                        });
    }

    @Test
    void everyModuleDeclaresOneInternalImplementationPackage() {
        Set<String> owners =
                PRODUCTION_CLASSES.stream()
                        .filter(javaClass -> javaClass.isAnnotatedWith(ModuleInternal.class))
                        .map(
                                javaClass -> {
                                    ModuleDeclaration owner =
                                            moduleForPackage(javaClass.getPackageName())
                                                    .orElseThrow(
                                                            () ->
                                                                    new AssertionError(
                                                                            "Internal package has no owning module: "
                                                                                    + javaClass
                                                                                            .getPackageName()));
                                    assertEquals(
                                            owner.basePackage() + ".internal",
                                            javaClass.getPackageName(),
                                            "The internal marker must be on the module's internal package");
                                    return owner.name();
                                })
                        .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                EXPECTED_MODULES,
                owners,
                "Every module must declare exactly one internal implementation package");
    }

    @Test
    void declaredModuleDependencyGraphIsAcyclic() {
        Set<String> visited = new HashSet<>();
        MODULES.keySet().forEach(module -> visit(module, visited, new ArrayDeque<>()));
        assertEquals(
                EXPECTED_MODULES,
                visited,
                "The acyclic traversal must visit every declared module");
    }

    @Test
    void bytecodeDependenciesFollowTheDeclaredModuleGraphAndPublicApiRule() {
        declaredModuleDependencyRule().check(PRODUCTION_CLASSES);
    }

    @Test
    void repositoriesCannotBeUsedAcrossModuleBoundaries() {
        crossModuleRepositoryRule().check(PRODUCTION_CLASSES);
    }

    @Test
    void moduleBytecodeDependenciesAreFreeOfCycles() {
        slices().matching(ROOT_PACKAGE + ".(*)..")
                .should()
                .beFreeOfCycles()
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void publicApiRuleRejectsAnIntentionalInternalPackageViolation() {
        JavaClasses violationFixture =
                new ClassFileImporter()
                        .importClasses(
                                TenantToRuntimeInternalViolation.class, RuntimeInternalProbe.class);

        AssertionError violation =
                assertThrows(
                        AssertionError.class,
                        () -> declaredModuleDependencyRule().check(violationFixture),
                        "An intentional internal-package dependency must be rejected");

        assertTrue(
                violation.getMessage().contains("accesses a non-public package of module runtime"),
                () -> "Unexpected architecture violation: " + violation.getMessage());
    }

    @Test
    void dependencyRuleRejectsAnIntentionalUndeclaredModuleDependency() {
        JavaClasses violationFixture =
                new ClassFileImporter()
                        .importClasses(
                                TenantToRuntimePublicViolation.class, RuntimePublicProbe.class);

        AssertionError violation =
                assertThrows(
                        AssertionError.class,
                        () -> declaredModuleDependencyRule().check(violationFixture),
                        "An intentional undeclared module dependency must be rejected");

        assertTrue(
                violation.getMessage().contains("module tenant may not depend on module runtime"),
                () -> "Unexpected architecture violation: " + violation.getMessage());
    }

    @Test
    void repositoryRuleRejectsAnIntentionalCrossModuleRepositoryViolation() {
        JavaClasses violationFixture =
                new ClassFileImporter()
                        .importClasses(
                                RuntimeToDefinitionRepositoryViolation.class,
                                DefinitionRepository.class);

        AssertionError violation =
                assertThrows(
                        AssertionError.class,
                        () -> crossModuleRepositoryRule().check(violationFixture),
                        "An intentional cross-module Repository dependency must be rejected");

        assertTrue(
                violation
                        .getMessage()
                        .contains("directly accesses Repository owned by module definition"),
                () -> "Unexpected architecture violation: " + violation.getMessage());
    }

    private static ArchRule declaredModuleDependencyRule() {
        return classes()
                .that()
                .resideInAPackage(ROOT_PACKAGE + "..")
                .should(respectDeclaredModuleDependenciesAndPublicApis());
    }

    private static ArchRule crossModuleRepositoryRule() {
        return classes()
                .that()
                .resideInAPackage(ROOT_PACKAGE + "..")
                .should(notAccessAnotherModulesRepository());
    }

    private static ArchCondition<JavaClass> respectDeclaredModuleDependenciesAndPublicApis() {
        return new ArchCondition<>(
                "respect declared module dependencies and only use another module's root API") {
            @Override
            public void check(JavaClass originClass, ConditionEvents events) {
                Optional<ModuleDeclaration> originModule =
                        moduleForPackage(originClass.getPackageName());

                for (Dependency dependency : originClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    Optional<ModuleDeclaration> targetModule =
                            moduleForPackage(targetClass.getPackageName());

                    if (targetModule.isEmpty()
                            || (originModule.isPresent()
                                    && originModule
                                            .get()
                                            .name()
                                            .equals(targetModule.get().name()))) {
                        continue;
                    }

                    if (!targetClass.getPackageName().equals(targetModule.get().basePackage())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription()
                                                + " accesses a non-public package of module "
                                                + targetModule.get().name()));
                        continue;
                    }

                    if (originModule.isPresent()
                            && !originModule
                                    .get()
                                    .allowedDependencies()
                                    .contains(targetModule.get().name())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription()
                                                + "; module "
                                                + originModule.get().name()
                                                + " may not depend on module "
                                                + targetModule.get().name()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notAccessAnotherModulesRepository() {
        return new ArchCondition<>("not access a Repository owned by another module") {
            @Override
            public void check(JavaClass originClass, ConditionEvents events) {
                Optional<ModuleDeclaration> originModule =
                        moduleForPackage(originClass.getPackageName());

                for (Dependency dependency : originClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    Optional<ModuleDeclaration> targetModule =
                            moduleForPackage(targetClass.getPackageName());

                    boolean sameModule =
                            originModule.isPresent()
                                    && targetModule.isPresent()
                                    && originModule.get().name().equals(targetModule.get().name());

                    if (targetModule.isPresent()
                            && !sameModule
                            && targetClass.getSimpleName().endsWith("Repository")) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        dependency,
                                        dependency.getDescription()
                                                + " directly accesses Repository owned by module "
                                                + targetModule.get().name()));
                    }
                }
            }
        };
    }

    private static Map<String, ModuleDeclaration> discoverModules() {
        Map<String, ModuleDeclaration> modules = new TreeMap<>();

        PRODUCTION_CLASSES.stream()
                .filter(javaClass -> javaClass.getName().endsWith(".package-info"))
                .filter(javaClass -> javaClass.isAnnotatedWith(ModuleBoundary.class))
                .forEach(
                        javaClass -> {
                            ModuleBoundary boundary =
                                    javaClass.getAnnotationOfType(ModuleBoundary.class);
                            ModuleDeclaration declaration =
                                    new ModuleDeclaration(
                                            boundary.value(),
                                            javaClass.getPackageName(),
                                            Set.copyOf(
                                                    Arrays.asList(boundary.allowedDependencies())));
                            ModuleDeclaration previous = modules.put(boundary.value(), declaration);
                            if (previous != null) {
                                throw new IllegalStateException(
                                        "Duplicate module declaration: " + boundary.value());
                            }
                        });

        return Collections.unmodifiableMap(modules);
    }

    private static Optional<ModuleDeclaration> moduleForPackage(String packageName) {
        return MODULES.values().stream()
                .filter(
                        module ->
                                packageName.equals(module.basePackage())
                                        || packageName.startsWith(module.basePackage() + "."))
                .findFirst();
    }

    private static void visit(String module, Set<String> visited, Deque<String> path) {
        if (visited.contains(module)) {
            return;
        }
        if (path.contains(module)) {
            path.addLast(module);
            fail("Declared module dependency cycle: " + String.join(" -> ", path));
        }

        path.addLast(module);
        MODULES.get(module)
                .allowedDependencies()
                .forEach(dependency -> visit(dependency, visited, path));
        path.removeLast();
        visited.add(module);
    }

    private record ModuleDeclaration(
            String name, String basePackage, Set<String> allowedDependencies) {}
}
