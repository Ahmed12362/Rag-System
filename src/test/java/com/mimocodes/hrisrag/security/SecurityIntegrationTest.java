package com.mimocodes.hrisrag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.document.ingestion.IngestionService;
import com.mimocodes.hrisrag.testsupport.FakeModels;
import com.mimocodes.hrisrag.testsupport.FakeModels.ScriptedChatModel;

/** HTTP-level security: authentication, identity from the principal only, role-based retrieval, health exposure. */
@Import(FakeModels.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityIntegrationTest extends IntegrationTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder encoder;
	@Autowired IngestionService ingestion;
	@LocalServerPort int port;

	@TempDir
	Path corpus;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void fixture() throws Exception {
		jdbc.execute("DELETE FROM source_document");
		jdbc.execute("DELETE FROM rag_request_trace");
		for (String f : List.of("en/compensation/CONF-SALARY-BANDS-EN--v1.0.md", "en/leave/POL-ANNUAL-LEAVE-EN--v2.0.md")) {
			Path target = corpus.resolve(f);
			Files.createDirectories(target.getParent());
			Files.copy(Path.of("corpus").resolve(f), target);
		}
		assertThat(ingestion.ingest(corpus).status()).isEqualTo("SUCCEEDED");
		ScriptedChatModel.RESPONDER.set(p -> "Answer [S1].");
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> chat(String authorization, String json) throws Exception {
		var b = HttpRequest.newBuilder(uri("/api/v1/chat")).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (authorization != null) {
			b.header("Authorization", authorization);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		var b = HttpRequest.newBuilder(uri(path));
		if (authorization != null) {
			b.header("Authorization", authorization);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void apiRequiresValidCredentials(CapturedOutput output) throws Exception {
		String q = "{\"question\":\"How many annual leave days do I get?\"}";
		var none = chat(null, q);
		assertThat(none.statusCode()).isEqualTo(401);
		assertThat(none.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic");
		assertThat(none.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");

		var wrongPassword = chat(basic("employee", "not-the-password"), q);
		var unknownUser = chat(basic("nobody", DEMO_PASSWORD), q);
		assertThat(wrongPassword.statusCode()).isEqualTo(401);
		assertThat(unknownUser.statusCode()).isEqualTo(401);
		assertThat(wrongPassword.body()).isEqualTo(unknownUser.body());          // no account enumeration
		assertThat(wrongPassword.body()).doesNotContain("not-the-password").doesNotContain("employee");

		assertThat(get("/api/v1/strategies", null).statusCode()).isEqualTo(401);
		assertThat(chat(basic("employee", DEMO_PASSWORD), q).statusCode()).isEqualTo(200);

		// credentials never appear in logs (failed or successful attempts)
		assertThat(output.getAll()).doesNotContain(DEMO_PASSWORD).doesNotContain("not-the-password");
	}

	@Test
	void passwordsAreStoredOnlyAsBcryptHashes() {
		List<String> hashes = jdbc.queryForList("SELECT password_hash FROM app_user", String.class);
		assertThat(hashes).hasSize(6).allSatisfy(h -> {
			assertThat(h).startsWith("$2").doesNotContain(DEMO_PASSWORD);
			assertThat(encoder.matches(DEMO_PASSWORD, h)).isTrue();
		});
	}

	@Test
	void meReturnsTheAuthenticatedIdentityWithoutCredentials() throws Exception {
		var me = get("/api/v1/me", basic("hr", DEMO_PASSWORD));
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"employeeCode\":\"E-1003\"").contains("\"role\":\"HR_ADMIN\"")
				.contains("\"maxAccessLevel\":\"CONFIDENTIAL\"").doesNotContainIgnoringCase("password");
		assertThat(get("/api/v1/me", basic("contractor", DEMO_PASSWORD)).body())
				.contains("\"maxAccessLevel\":\"PUBLIC\"");
	}

	@Test
	void identityCannotBeChosenInTheRequestBody() throws Exception {
		var r = chat(basic("employee", DEMO_PASSWORD), "{\"question\":\"salary band monthly EGP team lead\","
				+ "\"employeeCode\":\"E-1003\",\"role\":\"HR_ADMIN\",\"department\":\"D-HR\",\"maxAccessLevel\":\"CONFIDENTIAL\"}");
		// Unknown fields are either rejected or ignored; they must never change the identity.
		if (r.statusCode() == 200) {
			assertThat(r.body()).doesNotContain("CONF-SALARY-BANDS-EN");
			assertThat(jdbc.queryForObject("SELECT user_code FROM rag_request_trace ORDER BY id DESC LIMIT 1",
					String.class)).isEqualTo("E-1001");
		}
		else {
			assertThat(r.statusCode()).isEqualTo(400);
		}
	}

	@Test
	void roleDecidesWhichDocumentsAreRetrievedOverHttp() throws Exception {
		String q = "{\"question\":\"salary band monthly EGP team lead department head\"}";
		var employee = chat(basic("employee", DEMO_PASSWORD), q);
		var manager = chat(basic("manager", DEMO_PASSWORD), q);
		var hr = chat(basic("hr", DEMO_PASSWORD), q);
		assertThat(employee.body()).doesNotContain("CONF-SALARY-BANDS-EN");
		assertThat(manager.body()).doesNotContain("CONF-SALARY-BANDS-EN");
		assertThat(hr.body()).contains("CONF-SALARY-BANDS-EN");
		assertThat(jdbc.queryForList("SELECT user_code FROM rag_request_trace ORDER BY id", String.class))
				.containsExactly("E-1001", "E-1000", "E-1003");
	}

	@Test
	void healthIsPublicWithoutDetails() throws Exception {
		var anonymous = get("/actuator/health", null);
		assertThat(anonymous.statusCode()).isEqualTo(200);
		assertThat(anonymous.body()).contains("\"status\":\"UP\"").doesNotContain("components");

		var employee = get("/actuator/health", basic("employee", DEMO_PASSWORD));
		assertThat(employee.body()).doesNotContain("components");
		var hr = get("/actuator/health", basic("hr", DEMO_PASSWORD));
		assertThat(hr.body()).contains("components").contains("\"db\"");

		assertThat(get("/actuator/env", null).statusCode()).isIn(401, 403, 404);
		assertThat(get("/actuator/env", basic("hr", DEMO_PASSWORD)).statusCode()).isIn(403, 404);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
