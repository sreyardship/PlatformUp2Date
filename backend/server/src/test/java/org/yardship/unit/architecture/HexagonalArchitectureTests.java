package org.yardship.unit.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The hexagon's arrows, as a test.
 *
 * <p>These rules replace the {@code :backend:domain} Gradle edge. Today that edge makes an illegal
 * import a compile error: the domain module cannot see Quarkus, JAX-RS, CDI or the adapters,
 * because none of them are on its classpath. Once the module is folded into {@code :backend:server}
 * every one of them is one {@code import} away, so a compile error becomes a test failure here —
 * deliberately, and with the swap proven while the compiler still holds the line.
 *
 * <p>{@code ConfigError} and {@code ConfigErrorScope} count as domain by ADR-0035: the structure
 * carries no substrate vocabulary, so ADR-0005 is satisfied and {@code TargetResult} is the
 * precedent.
 *
 * <p>Both rules are written as allowlists and deny by default. The guarantee a module boundary
 * gives is a list of permitted dependencies, never a list of forbidden ones — a rule set naming
 * what is banned only catches what someone thought to write down.
 */
@AnalyzeClasses(packages = "org.yardship", importOptions = ImportOption.DoNotIncludeTests.class)
public class HexagonalArchitectureTests {

    /**
     * Who may reach each layer. Nothing outside the listed layers may, which is what makes this the
     * module edge's replacement rather than a sample of it.
     */
    @ArchTest
    static final ArchRule theHexagonsArrowsPointInwards = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("org.yardship..")

            .layer("core.domain").definedBy("org.yardship.core.domain..")
            .layer("core.ports.in").definedBy("org.yardship.core.ports.in..")
            .layer("core.ports.out").definedBy("org.yardship.core.ports.out..")
            .layer("core.services").definedBy("org.yardship.core.services..")
            .layer("adapters.in").definedBy("org.yardship.adapters.in..")
            .layer("adapters.out").definedBy("org.yardship.adapters.out..")

            // The domain is what everything shares, and it names nobody: any dependency it took on
            // another layer would break that layer's own access list below.
            .whereLayer("core.domain").mayOnlyBeAccessedByLayers(
                    "core.ports.in", "core.ports.out", "core.services", "adapters.in", "adapters.out")

            // A driving adapter asks the core through the driving port; a use case answers it.
            .whereLayer("core.ports.in").mayOnlyBeAccessedByLayers("adapters.in", "core.services")

            // A use case asks the driven side through the driven port; a driven adapter implements
            // it. A Surface never sees a driven type — that is what the scrape-state exception pair
            // and the config-error ports are for.
            .whereLayer("core.ports.out").mayOnlyBeAccessedByLayers("adapters.out", "core.services")

            // Every layer below is reached by wiring, never by an import: CDI resolves an
            // implementation from the port its caller declares.
            .whereLayer("core.services").mayNotBeAccessedByAnyLayer()
            .whereLayer("adapters.in").mayNotBeAccessedByAnyLayer()
            .whereLayer("adapters.out").mayNotBeAccessedByAnyLayer()

            .because("a driving adapter reaches the core through core.ports.in and nothing else, "
                    + "and no layer reaches sideways into an adapter (ADR-0035)");

    /**
     * What the domain may depend on at all. The layered DSL cannot say this — it only knows the
     * layers we declared — and this is the half of the module boundary that actually kept Quarkus,
     * JAX-RS and CDI out of the domain. {@code io.quarkus.runtime.annotations} is allowed for the
     * {@code @RegisterForReflection} the native image needs on five records.
     */
    @ArchTest
    static final ArchRule theDomainDependsOnAlmostNothing = classes()
            .that().resideInAPackage("org.yardship.core.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "org.semver4j..",
                    "io.quarkus.runtime.annotations..",
                    "org.yardship.core.domain..")
            .because("the domain carried no framework when Gradle enforced it, and must carry none "
                    + "once only this rule does");
}
