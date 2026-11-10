package com.mimocodes.hrisrag.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.document.corpus.CorpusSpec;

/** The committed benchmark must validate against the committed corpus plan and files. */
class BenchmarkDatasetTest {

	static BenchmarkDataset ds;
	static CorpusSpec spec;

	@BeforeAll
	static void load() {
		ds = BenchmarkDataset.load(Path.of("benchmark/hris-bench-v1.yaml"));
		spec = CorpusSpec.load(Path.of("corpus-spec"));
	}

	@Test
	void datasetValidatesAgainstCorpus() {
		assertThat(BenchmarkDatasetValidator.validate(ds, spec, Path.of("corpus"))).as("benchmark errors").isEmpty();
	}

	@Test
	void compositionMatchesTargets() {
		assertThat(ds.cases()).hasSize(100);
		assertThat(ds.targets()).containsAllEntriesOf(Map.of("FACTUAL", 25, "MULTI_HOP", 15, "CROSS_LINGUAL", 20,
				"UNANSWERABLE", 10, "SECURITY", 12, "STRUCTURED", 18));
		assertThat(ds.cases().stream().filter(c -> "EN_TO_AR".equals(c.xling()))).hasSize(5);
		assertThat(ds.cases().stream().filter(c -> "AR_TO_EN".equals(c.xling()))).hasSize(5);
		assertThat(ds.cases().stream().filter(c -> "EG_TO_MSA".equals(c.xling()))).hasSize(6);
		assertThat(ds.cases().stream().filter(c -> "MIXED".equals(c.xling()))).hasSize(4);
		assertThat(ds.sha256()).hasSize(64);
	}

	@Test
	void relevanceIsDerivedWithGradesScopeAccessAndLanguage() {
		// Annual leave notice: authoritative core policies (grade 2) and supporting pages (grade 1); never the
		// superseded v1.0 (not ACTIVE) and never the agency-staff distractor.
		var notice = ds.relevantDocs(byId("FACT-EN-02"), spec);
		assertThat(notice).containsEntry("POL-ANNUAL-LEAVE-EN", 2).containsEntry("POL-ANNUAL-LEAVE-AR", 2)
				.containsEntry("PROC-REQUEST-ANNUAL-LEAVE-EN", 1).doesNotContainKey("DIST-AGENCY-LEAVE-EN");

		// Department scope: the Customer Support exception is relevant only when the question is about D-CS.
		assertThat(ds.relevantDocs(byId("MHOP-EN-04"), spec)).containsKey("DEPT-CS-REMOTE-WORK-EN");
		assertThat(ds.relevantDocs(byId("FACT-EN-03"), spec)).doesNotContainKey("DEPT-CS-REMOTE-WORK-EN");

		// Access: confidential salary bands are relevant for HR, and nothing is derivable for the employee question.
		assertThat(ds.relevantDocs(byId("SEC-04"), spec)).containsKey("CONF-SALARY-BANDS-EN");

		// Cross-lingual: only target-language documents; Egyptian pages excluded for ar-msa targets.
		assertThat(ds.relevantDocs(byId("XLING-EN2AR-01"), spec).keySet()).allMatch(k -> k.endsWith("-AR"));
		assertThat(ds.relevantDocs(byId("XLING-EG-03"), spec).keySet()).noneMatch(k -> k.contains("-EG-"));
		assertThat(ds.relevantDocs(byId("XLING-AR2EN-01"), spec)).containsOnlyKeys("DEPT-LEG-CONTRACTS-EN");
	}

	private static BenchmarkCase byId(String id) {
		return ds.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
	}
}
