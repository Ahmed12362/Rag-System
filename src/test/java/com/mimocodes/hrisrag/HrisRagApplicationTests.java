package com.mimocodes.hrisrag;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Infrastructure smoke test: the application context starts against a real pgvector database,
 * Flyway applies the baseline migration, and the health endpoint reports UP.
 * Uses a placeholder Gemini key: no model call is made here.
 */
class HrisRagApplicationTests extends IntegrationTestBase {

	@Autowired
	JdbcTemplate jdbc;

	@LocalServerPort
	int port;

	@Test
	void flywayBaselineMigrationApplied() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void pgvectorExtensionInstalled() {
		String version = jdbc.queryForObject(
				"select extversion from pg_extension where extname = 'vector'", String.class);
		assertThat(version).isEqualTo("0.8.7");
	}

	@Test
	void healthEndpointIsUpIncludingDatabase() throws Exception {
		// Details (including the db component) are shown to an authenticated HR_ADMIN only (Task 6.1).
		String hr = "Basic " + java.util.Base64.getEncoder()
				.encodeToString(("hr:" + DEMO_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
						.header("Authorization", hr).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"db\"");
	}
}
