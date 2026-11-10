package com.mimocodes.hrisrag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module dependency rules from docs/architecture.md §3 (decision D9).
 * Rules about modules that do not exist yet use allowEmptyShould(true): they pass today and start enforcing
 * automatically as soon as those packages appear.
 */
@AnalyzeClasses(packages = "com.mimocodes.hrisrag", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

	private static final String ROOT = "com.mimocodes.hrisrag";

	/** Rule 5: the HRIS data module is a leaf; it depends on no other feature module. */
	@ArchTest
	static final ArchRule dataDependsOnNoFeatureModule = noClasses()
			.that().resideInAPackage(ROOT + ".data..")
			.should().dependOnClassesThat().resideInAnyPackage(
					ROOT + ".rag..", ROOT + ".tools..", ROOT + ".guardrails..", ROOT + ".document..",
					ROOT + ".evaluation..", ROOT + ".benchmark..", ROOT + ".experiment..")
			.because("data is the HRIS source of truth and must not depend on RAG or evaluation code");

	/** Rule 3: answering code never sees benchmark labels or metrics. */
	@ArchTest
	static final ArchRule ragDoesNotDependOnBenchmarkOrEvaluation = noClasses()
			.that().resideInAPackage(ROOT + ".rag..")
			.should().dependOnClassesThat().resideInAnyPackage(ROOT + ".benchmark..", ROOT + ".evaluation..")
			.because("benchmark labels must never reach the answering pipeline")
			.allowEmptyShould(true);

	/** Rule 1: other modules use data through services/DTOs, never its repositories directly. */
	@ArchTest
	static final ArchRule dataRepositoriesOnlyUsedInsideData = noClasses()
			.that().resideOutsideOfPackage(ROOT + ".data..")
			.should().dependOnClassesThat().resideInAPackage(ROOT + ".data..repository..")
			.because("cross-module access goes through services, so authorization can be enforced in one place");

	/** Rule 4: tools never touch SQL directly; they call data services. */
	@ArchTest
	static final ArchRule toolsDoNotUseJdbcOrEntityManager = noClasses()
			.that().resideInAPackage(ROOT + ".tools..")
			.should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
			.orShould().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager")
			.because("tools must not execute SQL; no arbitrary queries")
			.allowEmptyShould(true);

	/** Rule 6: controllers do not expose JPA entities. */
	@ArchTest
	static final ArchRule controllersDoNotUseEntities = noClasses()
			.that().resideInAPackage("..controller..")
			.should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
			.because("API responses are DTOs, never entities")
			.allowEmptyShould(true);
}
