package com.mimocodes.hrisrag.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.benchmark.BenchmarkCase;
import com.mimocodes.hrisrag.benchmark.BenchmarkDataset;
import com.mimocodes.hrisrag.document.ingestion.IngestionService;
import com.mimocodes.hrisrag.rag.prompt.GroundedPrompt;
import com.mimocodes.hrisrag.testsupport.FakeModels;
import com.mimocodes.hrisrag.testsupport.FakeModels.ScriptedChatModel;

/** Benchmark runner on the real dataset file, real schema, a small real corpus and fake models. */
@Import(FakeModels.Config.class)
class BenchmarkIntegrationTest extends IntegrationTestBase {

	// dataset order: FACT-EN-01, UNANS-EN-01, SEC-03, SEC-07, SEC-09
	static final String CASES = "SEC-09,FACT-EN-01,SEC-07,UNANS-EN-01,SEC-03";

	@Autowired ExperimentService service;
	@Autowired ExperimentStore store;
	@Autowired IngestionService ingestion;
	@Autowired JdbcTemplate jdbc;
	@LocalServerPort int port;

	@TempDir
	Path corpus;

	final BenchmarkDataset dataset = BenchmarkDataset.load(Path.of("benchmark/hris-bench-v1.yaml"));

	@BeforeEach
	void fixture() throws Exception {
		jdbc.execute("DELETE FROM experiment");
		jdbc.execute("DELETE FROM benchmark_dataset");
		jdbc.execute("DELETE FROM source_document");
		jdbc.execute("DELETE FROM rag_request_trace");
		for (String f : List.of("en/leave/POL-ANNUAL-LEAVE-EN--v2.0.md", "en/compensation/CONF-SALARY-BANDS-EN--v1.0.md",
				"en/facilities/INJ-PARKING-NOTICE-EN--v1.0.md")) {
			Path target = corpus.resolve(f);
			Files.createDirectories(target.getParent());
			Files.copy(Path.of("corpus").resolve(f), target);
		}
		assertThat(ingestion.ingest(corpus).status()).isEqualTo("SUCCEEDED");
		ScriptedChatModel.PROMPTS.clear();
		ScriptedChatModel.RESPONDER.set(p -> {
			String q = p.getUserMessage().getText();
			if (q.contains("Question: How many days of paid annual leave")) {
				return "You get 21 working days of paid annual leave [S1].";
			}
			if (q.contains("Question: What should I know about using the parking area")) {
				return "Report a lost badge within 24 hours [S1].";
			}
			return GroundedPrompt.INSUFFICIENT;
		});
	}

	@Test
	void runsScoresPersistsAndNeverLeaksLabelsIntoPrompts() {
		var s = service.run(new ExperimentService.Request(CASES, 0, null));

		assertThat(s.status()).isEqualTo("COMPLETED");
		assertThat(s.results()).extracting(ExperimentStore.ResultRow::caseKey)
				.containsExactly("FACT-EN-01", "UNANS-EN-01", "SEC-03", "SEC-07", "SEC-09");
		Map<String, String> status = Map.of("FACT-EN-01", "ANSWERED", "UNANS-EN-01", "ABSTAINED", "SEC-03", "ABSTAINED",
				"SEC-07", "BLOCKED", "SEC-09", "ANSWERED");
		assertThat(s.results()).allSatisfy(r -> {
			assertThat(r.answerStatus()).as(r.caseKey()).isEqualTo(status.get(r.caseKey()));
			assertThat(r.metrics().get("case_pass")).as(r.caseKey()).isEqualTo(1.0);
		});
		var fact = s.results().getFirst().metrics();
		assertThat(fact.get("must_contain_pass")).isEqualTo(1.0);
		assertThat(fact.get("recall_at_k")).isNotNull();

		// Aggregates per slice, with sample sizes.
		assertThat(s.aggregates()).anyMatch(a -> a.metric().equals("case_pass") && a.sliceType().equals("ALL")
				&& a.value() == 1.0 && a.sampleSize() == 5);
		assertThat(s.aggregates()).anyMatch(a -> a.metric().equals("security_pass") && a.sliceType().equals("CATEGORY")
				&& a.sliceValue().equals("SECURITY") && a.sampleSize() == 3);
		assertThat(s.aggregates()).anyMatch(a -> a.metric().equals("chat_calls_total") && a.value() == 4.0); // SEC-07 blocked
		assertThat(s.aggregates()).anyMatch(a -> a.sliceType().equals("SCOPE") && a.sliceValue().equals("RAG_ONLY"));
		assertThat(store.aggregates(s.runId())).hasSameSizeAs(s.aggregates());

		// Experiment config is a reproducible snapshot without secrets.
		String config = jdbc.queryForObject("SELECT config::text FROM experiment WHERE id = ?", String.class,
				s.experimentId());
		assertThat(config).contains(dataset.sha256()).contains("grounded-v1").contains("\"documents\"")
				.doesNotContain(DEMO_PASSWORD).doesNotContain("test-placeholder");

		// Labels never reach the model: every prompt is exactly the grounded system prompt plus sources and the question.
		assertThat(ScriptedChatModel.PROMPTS).hasSize(4);
		List<String> questions = s.results().stream().map(r -> caseById(r.caseKey()).question()).toList();
		assertThat(ScriptedChatModel.PROMPTS).allSatisfy(p -> {
			assertThat(p.getSystemMessage().getText()).isEqualTo(GroundedPrompt.SYSTEM);
			String user = p.getUserMessage().getText();
			assertThat(questions).anyMatch(q -> user.endsWith("\n\nQuestion: " + q));
			assertThat(user).doesNotContain("must_contain").doesNotContain("expected_behavior");
		});
		// The asker identity came from app_user (E-1001 for all five cases).
		assertThat(jdbc.queryForList("SELECT DISTINCT user_code FROM rag_request_trace", String.class))
				.containsExactly("E-1001");
	}

	@Test
	void quotaStopsAsPartialAndResumeSkipsFinishedCases() {
		ScriptedChatModel.RESPONDER.set(p -> {
			if (p.getUserMessage().getText().contains("parking area")) {
				throw new RuntimeException("429 RESOURCE_EXHAUSTED: quota exceeded");
			}
			return GroundedPrompt.INSUFFICIENT;
		});
		var first = service.run(new ExperimentService.Request(CASES, 0, null));
		assertThat(first.status()).isEqualTo("PARTIAL");
		assertThat(first.stopReason()).contains("SEC-09").contains("--hrisrag.benchmark.resume=" + first.experimentId());
		assertThat(first.results()).hasSize(4);                               // SEC-09 not stored: it is retried later
		assertThat(store.experiment(first.experimentId()).orElseThrow().status()).isEqualTo("PARTIAL");

		ScriptedChatModel.RESPONDER.set(p -> "Report a lost badge within 24 hours [S1].");
		ScriptedChatModel.PROMPTS.clear();
		var resumed = service.run(new ExperimentService.Request(null, 0, first.experimentId()));
		assertThat(resumed.status()).isEqualTo("COMPLETED");
		assertThat(resumed.experimentId()).isEqualTo(first.experimentId());
		assertThat(resumed.alreadyDone()).isEqualTo(4);
		assertThat(resumed.executed()).isEqualTo(1);
		assertThat(ScriptedChatModel.PROMPTS).hasSize(1);                     // only SEC-09 was asked again
		assertThat(resumed.results()).hasSize(5);
		assertThat(resumed.aggregates()).anyMatch(a -> a.metric().equals("behavior_correct")
				&& a.sliceType().equals("ALL") && a.sampleSize() == 5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM question_result", Integer.class)).isEqualTo(5);
	}

	@Test
	void limitAndPatternsSelectCasesInDatasetOrder() {
		assertThat(ExperimentService.select(dataset, "SEC-0*", 0)).extracting(BenchmarkCase::id)
				.containsExactly("SEC-01", "SEC-02", "SEC-03", "SEC-04", "SEC-05", "SEC-06", "SEC-07", "SEC-08", "SEC-09");
		assertThat(ExperimentService.select(dataset, "", 3)).hasSize(3);
		assertThat(ExperimentService.select(dataset, null, 0)).hasSize(100);
		assertThat(ExperimentService.select(dataset, "FACT-AR-01, UNANS-*", 0)).extracting(BenchmarkCase::id)
				.startsWith("FACT-AR-01").contains("UNANS-EN-01", "UNANS-AR-01", "UNANS-EG-01").hasSize(11);
	}

	@Test
	void changedDatasetFileIsRefused() {
		store.registerDataset(dataset);
		var tampered = new BenchmarkDataset(dataset.name(), dataset.version(), dataset.referenceDate(), dataset.targets(),
				dataset.askers(), dataset.tools(), dataset.cases(), "0".repeat(64));
		assertThatThrownBy(() -> store.registerDataset(tampered))
				.isInstanceOf(ExperimentStore.DatasetChangedException.class)
				.hasMessageContaining("changed since it was registered");
	}

	@Test
	void experimentEndpointsAreHrOnly() throws Exception {
		var s = service.run(new ExperimentService.Request("SEC-07", 0, null));
		HttpClient http = HttpClient.newHttpClient();
		var employee = http.send(get("/api/v1/experiments", "employee"), HttpResponse.BodyHandlers.ofString());
		assertThat(employee.statusCode()).isEqualTo(403);
		var anonymous = http.send(HttpRequest.newBuilder(uri("/api/v1/experiments")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);

		var list = http.send(get("/api/v1/experiments", "hr"), HttpResponse.BodyHandlers.ofString());
		assertThat(list.statusCode()).isEqualTo(200);
		assertThat(list.body()).contains("\"id\":" + s.experimentId()).contains("\"status\":\"COMPLETED\"");
		var detail = http.send(get("/api/v1/experiments/" + s.experimentId(), "hr"), HttpResponse.BodyHandlers.ofString());
		assertThat(detail.body()).contains("\"case_key\":\"SEC-07\"").contains("\"metric\":\"case_pass\"");
		var missing = http.send(get("/api/v1/experiments/999999", "hr"), HttpResponse.BodyHandlers.ofString());
		assertThat(missing.statusCode()).isEqualTo(404);
	}

	private BenchmarkCase caseById(String id) {
		return dataset.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
	}

	private HttpRequest get(String path, String user) {
		return HttpRequest.newBuilder(uri(path)).header("Authorization", "Basic " + Base64.getEncoder()
				.encodeToString((user + ":" + DEMO_PASSWORD).getBytes(StandardCharsets.UTF_8))).build();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
