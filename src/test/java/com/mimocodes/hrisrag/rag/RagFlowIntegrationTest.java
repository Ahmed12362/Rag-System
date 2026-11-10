package com.mimocodes.hrisrag.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.document.ingestion.IngestionService;
import com.mimocodes.hrisrag.rag.core.RagContract.AnswerStatus;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.retrieval.VectorRetriever;
import com.mimocodes.hrisrag.rag.service.RagService;
import com.mimocodes.hrisrag.testsupport.FakeModels;
import com.mimocodes.hrisrag.testsupport.FakeModels.HashingEmbeddingModel;
import com.mimocodes.hrisrag.testsupport.FakeModels.ScriptedChatModel;

/** Naive RAG end to end on real corpus files and the real schema, with deterministic fake models (no API calls). */
@Import(FakeModels.Config.class)
class RagFlowIntegrationTest extends IntegrationTestBase {

	static final UserContext EMPLOYEE = new UserContext("E-1001", "EMPLOYEE", "D-ENG", "INTERNAL");
	static final UserContext HR = new UserContext("E-1003", "HR_ADMIN", "D-HR", "CONFIDENTIAL");
	static final UserContext CS_AGENT = new UserContext("E-2300", "EMPLOYEE", "D-CS", "INTERNAL");

	@Autowired IngestionService ingestion;
	@Autowired VectorRetriever retriever;
	@Autowired RagService rag;
	@Autowired RagProperties props;
	@Autowired JdbcTemplate jdbc;
	@LocalServerPort int port;

	@TempDir
	Path corpus;

	@BeforeEach
	void ingestSmallCorpus() throws Exception {
		jdbc.execute("DELETE FROM source_document");
		jdbc.execute("DELETE FROM embedding_cache");
		jdbc.execute("DELETE FROM rag_request_trace");
		for (String f : List.of("en/leave/POL-ANNUAL-LEAVE-EN--v2.0.md", "en/leave/POL-ANNUAL-LEAVE-EN--v1.0.md",
				"en/compensation/CONF-SALARY-BANDS-EN--v1.0.md", "en/remote-work/DEPT-CS-REMOTE-WORK-EN--v1.0.md",
				"en/remote-work/POL-REMOTE-WORK-EN--v2.0.md", "en/remote-work/POL-REMOTE-WORK-EN--v3.0.md",
				"en/attendance/FAQ-ATTENDANCE-1-EN--v1.0.md")) {
			Path target = corpus.resolve(f);
			Files.createDirectories(target.getParent());
			Files.copy(Path.of("corpus").resolve(f), target);
		}
		assertThat(ingestion.ingest(corpus).status()).isEqualTo("SUCCEEDED");
		ScriptedChatModel.PROMPTS.clear();
	}

	private List<RetrievedChunk> retrieve(String q, UserContext u, int k) {
		return retriever.retrieve(HashingEmbeddingModel.vector(q), u, k);
	}

	@Test
	void retrievalFiltersStatusAccessAndDepartmentInSql() {
		var employee = retrieve("salary band monthly EGP team lead confidential", EMPLOYEE, 50);
		assertThat(employee).extracting(RetrievedChunk::docKey).doesNotContain("CONF-SALARY-BANDS-EN");
		assertThat(employee).allMatch(c -> !c.version().equals("1.0") || !c.docKey().equals("POL-ANNUAL-LEAVE-EN"));
		assertThat(employee).extracting(RetrievedChunk::docKey).doesNotContain("DEPT-CS-REMOTE-WORK-EN");
		assertThat(employee).noneMatch(c -> c.docKey().equals("POL-REMOTE-WORK-EN") && c.version().equals("3.0"));

		assertThat(retrieve("salary band monthly EGP team lead", HR, 50)).extracting(RetrievedChunk::docKey)
				.contains("CONF-SALARY-BANDS-EN");
		assertThat(retrieve("customer support remote work on-site", CS_AGENT, 50)).extracting(RetrievedChunk::docKey)
				.contains("DEPT-CS-REMOTE-WORK-EN");
	}

	@Test
	void retrievalReturnsKRankedResultsEvenWithFilters() {
		var top = retrieve("annual leave request notice", EMPLOYEE, 5);
		assertThat(top).hasSize(5);
		assertThat(top).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3, 4, 5);
		assertThat(top).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
		assertThat(top.getFirst().docKey()).isEqualTo("POL-ANNUAL-LEAVE-EN");
	}

	@Test
	void answeredWithValidatedCitationsTraceAndQueryCache() {
		ScriptedChatModel.RESPONDER.set(p -> "Request leave at least 14 calendar days ahead [S1][S9].");
		RagResult r = rag.ask(StrategyId.NAIVE, new RagQuery("How early must I request annual leave?", "c-1", EMPLOYEE),
				props.defaultSettings());

		assertThat(r.status()).isEqualTo(AnswerStatus.ANSWERED);
		assertThat(r.answer()).contains("[S1]").doesNotContain("[S9]");
		assertThat(r.citations()).singleElement().satisfies(c -> assertThat(c.sourceId()).isEqualTo("S1"));
		assertThat(r.guardrails()).anyMatch(g -> g.rule().equals("CITATIONS") && g.outcome().equals("FLAG"));
		assertThat(r.retrieved()).hasSize(5);
		assertThat(r.modelCalls()).extracting(c -> c.purpose()).containsExactly("EMBED_QUERY", "GENERATE");
		assertThat(r.modelCalls().get(1).thinkingTokens()).isEqualTo(0);
		// the user prompt carried the sources; the system prompt carried the rules
		assertThat(ScriptedChatModel.PROMPTS.getFirst().getUserMessage().getText()).contains("<source id=\"S1\"");

		var row = jdbc.queryForMap("SELECT user_code, strategy, answer_status, prompt_template_version, trace::text AS t "
				+ "FROM rag_request_trace WHERE request_id = ?", r.requestId());
		assertThat(row).containsEntry("user_code", "E-1001").containsEntry("answer_status", "ANSWERED")
				.containsEntry("prompt_template_version", "grounded-v1");
		assertThat((String) row.get("t")).contains("\"docKey\"").doesNotContain("calendar days ahead [S1]");

		// Same question again: the query embedding comes from the cache (no provider call).
		int before = HashingEmbeddingModel.REQUESTS.get();
		RagResult again = rag.ask(StrategyId.NAIVE, new RagQuery("How early must I request annual leave?", "c-1",
				EMPLOYEE), props.defaultSettings());
		assertThat(HashingEmbeddingModel.REQUESTS.get()).isEqualTo(before);
		assertThat(again.modelCalls().getFirst().cached()).isTrue();
	}

	@Test
	void modelAbstentionAndProviderErrorsAreSafe() {
		ScriptedChatModel.RESPONDER.set(p -> "INSUFFICIENT_CONTEXT");
		RagResult abstained = rag.ask(StrategyId.NAIVE, new RagQuery("How long is maternity leave?", null, EMPLOYEE),
				props.defaultSettings());
		assertThat(abstained.status()).isEqualTo(AnswerStatus.ABSTAINED);
		assertThat(abstained.answer()).startsWith("I could not find");
		assertThat(abstained.citations()).isEmpty();

		ScriptedChatModel.RESPONDER.set(p -> {
			throw new RuntimeException("403 SERVICE_DISABLED project 769331917188 https://console.example/activate");
		});
		RagResult error = rag.ask(StrategyId.NAIVE, new RagQuery("ما هي سياسة الإجازة؟", null, EMPLOYEE),
				props.defaultSettings());
		assertThat(error.status()).isEqualTo(AnswerStatus.ERROR);
		assertThat(error.answer()).isEqualTo("المساعد غير متاح مؤقتاً. يرجى المحاولة لاحقاً.");
		// regression (real run, Task 5.1): raw provider errors must not reach the client or the trace
		assertThat(error.guardrails().toString()).doesNotContain("769331917188").doesNotContain("console.example")
				.contains("details in server logs");
	}

	@Test
	void httpApiValidatesAndAnswers() throws Exception {
		ScriptedChatModel.RESPONDER.set(p -> "You get 21 working days [S1].");
		var ok = post("{\"question\":\"How many annual leave days do I get?\"}");
		assertThat(ok.statusCode()).isEqualTo(200);
		assertThat(ok.body()).contains("\"status\":\"ANSWERED\"").contains("\"strategy\":\"NAIVE\"").contains("\"citations\"");

		var blank = post("{\"question\":\"  \"}");
		assertThat(blank.statusCode()).isEqualTo(400);
		assertThat(blank.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");

		var tooLong = post("{\"question\":\"" + "x".repeat(2001) + "\"}");
		assertThat(tooLong.statusCode()).isEqualTo(400);

		var agentic = post("{\"question\":\"hello\",\"strategy\":\"AGENTIC\"}");
		assertThat(agentic.statusCode()).isEqualTo(400);
		assertThat(agentic.body()).contains("not available yet");

		var strategies = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/strategies"))
				.header("Authorization", basic("employee")).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(strategies.body()).contains("{\"id\":\"NAIVE\",\"available\":true")
				.contains("{\"id\":\"HYBRID\",\"available\":true").contains("{\"id\":\"AGENTIC\",\"available\":false");
	}

	private HttpResponse<String> post(String json) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/chat"))
				.header("Content-Type", "application/json").header("Authorization", basic("employee"))
				.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
	}

	static String basic(String user) {
		return "Basic " + java.util.Base64.getEncoder()
				.encodeToString((user + ":" + DEMO_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
