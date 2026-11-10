package com.mimocodes.hrisrag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.ai.EmbeddingService;

/**
 * End-to-end incremental ingestion on the real Flyway schema (pgvector Testcontainer) with a deterministic fake
 * embedding model: no API calls. One ordered scenario: first run, unchanged re-run, edit, removal, corrupt file, search.
 */
@Import(IngestionIntegrationTest.FakeEmbeddingConfig.class)
class IngestionIntegrationTest extends IntegrationTestBase {

	/** Feature-hashing bag of words: texts sharing words get similar vectors, so similarity search is meaningful. */
	static final class HashingEmbeddingModel implements EmbeddingModel {
		static final AtomicInteger REQUESTS = new AtomicInteger();
		private static final Pattern WORD = Pattern.compile("\\p{L}+");

		static float[] vector(String text) {
			float[] v = new float[768];
			Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
			while (m.find()) {
				v[Math.floorMod(m.group().hashCode(), 768)] += 1;
			}
			double norm = 0;
			for (float x : v) {
				norm += x * x;
			}
			if (norm == 0) {
				v[0] = 1;
				return v;
			}
			for (int i = 0; i < v.length; i++) {
				v[i] /= (float) Math.sqrt(norm);
			}
			return v;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			REQUESTS.incrementAndGet();
			List<String> texts = request.getInstructions();
			return new EmbeddingResponse(IntStream.range(0, texts.size())
					.mapToObj(i -> new Embedding(vector(texts.get(i)), i)).toList());
		}

		@Override
		public float[] embed(Document document) {
			return vector(document.getText());
		}

		@Override
		public int dimensions() {
			return 768;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeEmbeddingConfig {
		@Bean
		@Primary
		EmbeddingModel fakeEmbeddingModel() {
			return new HashingEmbeddingModel();
		}
	}

	@Autowired IngestionService ingestion;
	@Autowired EmbeddingService embeddings;
	@Autowired JdbcTemplate jdbc;

	@TempDir
	Path corpus;

	@BeforeEach
	void clean() throws Exception {
		jdbc.execute("DELETE FROM source_document");
		jdbc.execute("DELETE FROM embedding_cache");
		jdbc.execute("DELETE FROM ingestion_run");
		for (String f : List.of("en/leave/POL-ANNUAL-LEAVE-EN--v2.0.md", "en/leave/POL-ANNUAL-LEAVE-EN--v1.0.md",
				"en/attendance/FAQ-ATTENDANCE-1-EN--v1.0.md", "ar/leave/POL-ANNUAL-LEAVE-AR--v2.0.md")) {
			Path target = corpus.resolve(f);
			Files.createDirectories(target.getParent());
			Files.copy(Path.of("corpus").resolve(f), target);
		}
	}

	@Test
	void incrementalIngestionLifecycle() throws Exception {
		// 1. First run: everything new.
		var first = ingestion.ingest(corpus);
		assertThat(first.status()).isEqualTo("SUCCEEDED");
		assertThat(first.ingested()).isEqualTo(4);
		assertThat(first.chunksWritten()).isGreaterThan(4);
		assertThat(first.embeddingsComputed()).isEqualTo(first.chunksWritten());
		assertThat(first.embeddingRequests()).isPositive();
		assertThat(count("SELECT count(*) FROM document_chunk")).isEqualTo(first.chunksWritten());
		assertThat(count("SELECT count(*) FROM source_document WHERE status = 'SUPERSEDED'")).isEqualTo(1);

		// 2. Re-run without changes: nothing re-embedded, zero provider requests.
		int before = HashingEmbeddingModel.REQUESTS.get();
		var second = ingestion.ingest(corpus);
		assertThat(second.unchanged()).isEqualTo(4);
		assertThat(second.ingested()).isZero();
		assertThat(second.embeddingRequests()).isZero();
		assertThat(HashingEmbeddingModel.REQUESTS.get()).isEqualTo(before);

		// 3. Edit one section of one document: only that document is re-ingested, only the changed chunk is embedded.
		Path edited = corpus.resolve("en/attendance/FAQ-ATTENDANCE-1-EN--v1.0.md");
		Files.writeString(edited, Files.readString(edited, StandardCharsets.UTF_8) + "\nAn added clarifying sentence.\n",
				StandardCharsets.UTF_8);
		var third = ingestion.ingest(corpus);
		assertThat(third.ingested()).isEqualTo(1);
		assertThat(third.unchanged()).isEqualTo(3);
		assertThat(third.embeddingsComputed()).isEqualTo(1);
		assertThat(third.embeddingsCached()).isEqualTo(third.chunksWritten() - 1);

		// 4. Remove a document from the corpus: it is deleted from the database.
		Files.delete(corpus.resolve("en/leave/POL-ANNUAL-LEAVE-EN--v1.0.md"));
		var fourth = ingestion.ingest(corpus);
		assertThat(fourth.removed()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM source_document WHERE status = 'SUPERSEDED'")).isZero();

		// 5. A corrupt file is recorded as a failure; the rest of the run continues.
		Path bad = corpus.resolve("en/leave/BROKEN--v1.0.md");
		Files.writeString(bad, "no front matter here", StandardCharsets.UTF_8);
		var fifth = ingestion.ingest(corpus);
		assertThat(fifth.status()).isEqualTo("PARTIAL");
		assertThat(fifth.failures()).singleElement().satisfies(f -> {
			assertThat(f.stage()).isEqualTo("PARSE");
			assertThat(f.sourcePath()).isEqualTo("en/leave/BROKEN--v1.0.md");
		});
		assertThat(fifth.unchanged()).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM ingestion_failure WHERE run_id = " + fifth.runId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM ingestion_run WHERE id = ?", String.class, fifth.runId()))
				.isEqualTo("PARTIAL");

		// 6. The stored data is searchable: vector top-1 and English full-text both find the annual-leave policy.
		String query = DocumentStore.toVectorLiteral(embeddings.embedQuery("annual leave request notice calendar days"));
		String top = jdbc.queryForObject("""
				SELECT d.doc_key FROM document_chunk c JOIN source_document d ON d.id = c.document_id
				WHERE d.status = 'ACTIVE' AND d.language = 'en'
				ORDER BY c.embedding <=> ?::vector LIMIT 1""", String.class, query);
		assertThat(top).isEqualTo("POL-ANNUAL-LEAVE-EN");
		assertThat(jdbc.queryForList("""
				SELECT DISTINCT d.doc_key FROM document_chunk c JOIN source_document d ON d.id = c.document_id
				WHERE c.search_vector @@ plainto_tsquery('english', 'carried over')""", String.class))
				.contains("POL-ANNUAL-LEAVE-EN");
	}

	private long count(String sql) {
		return jdbc.queryForObject(sql, Long.class);
	}
}
