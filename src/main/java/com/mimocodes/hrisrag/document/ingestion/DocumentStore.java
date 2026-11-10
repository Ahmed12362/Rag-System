package com.mimocodes.hrisrag.document.ingestion;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mimocodes.hrisrag.document.corpus.DocumentMetadata;
import com.mimocodes.hrisrag.document.ingestion.CorpusLoader.LoadedDocument;
import com.mimocodes.hrisrag.document.ingestion.SectionChunker.Chunk;

/** JDBC access to the document tables (decision D6: own SQL, no Spring AI vector store). */
@Repository
public class DocumentStore {

	/** What is stored for one document version: its file hash and the embedding model(s) of its chunks. */
	public record StoredDocument(long id, String docKey, String version, String contentHash, Set<String> embeddingModels) {
		public String key() {
			return docKey + "@" + version;
		}
	}

	private final JdbcTemplate jdbc;

	public DocumentStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, StoredDocument> storedDocuments() {
		Map<String, StoredDocument> out = new HashMap<>();
		jdbc.query("""
				SELECT d.id, d.doc_key, d.version, d.content_hash, c.embedding_model
				FROM source_document d LEFT JOIN document_chunk c ON c.document_id = d.id""", rs -> {
			String key = rs.getString("doc_key") + "@" + rs.getString("version");
			StoredDocument d = out.computeIfAbsent(key, k -> {
				try {
					return new StoredDocument(rs.getLong("id"), rs.getString("doc_key"), rs.getString("version"),
							rs.getString("content_hash"), new HashSet<>());
				}
				catch (java.sql.SQLException e) {
					throw new IllegalStateException(e);
				}
			});
			String model = rs.getString("embedding_model");
			if (model != null) {
				d.embeddingModels().add(model);
			}
		});
		return out;
	}

	public void delete(long documentId) {
		jdbc.update("DELETE FROM source_document WHERE id = ?", documentId); // chunks cascade
	}

	/** Replaces a document version (delete + insert). Must run inside the caller's transaction. */
	public int replace(LoadedDocument doc, List<Chunk> chunks, List<float[]> vectors, String model) {
		DocumentMetadata m = doc.metadata();
		jdbc.update("DELETE FROM source_document WHERE doc_key = ? AND version = ?", m.docKey(), m.version());
		Long id = jdbc.queryForObject("""
				INSERT INTO source_document (doc_key, version, title, category, doc_type, language, status, effective_date,
				  expiry_date, scope_department, access_level, source_path, content_hash, ingested_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
				m.docKey(), m.version(), m.title(), m.category(), m.docType(), m.language(), m.status(),
				Date.valueOf(m.effectiveDate()), m.expiryDate() == null ? null : Date.valueOf(m.expiryDate()),
				m.scopeDepartment(), m.accessLevel(), doc.relativePath(), doc.contentHash(), Timestamp.from(Instant.now()));
		List<Object[]> rows = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			Chunk c = chunks.get(i);
			rows.add(new Object[] { id, c.index(), c.sectionPath(), c.content(), c.embeddingText(), c.contentHash(),
					c.tokenCount(), m.language(), toVectorLiteral(vectors.get(i)), model });
		}
		jdbc.batchUpdate("""
				INSERT INTO document_chunk (document_id, chunk_index, section_path, content, embedding_text, content_hash,
				  token_count, language, embedding, embedding_model) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)""", rows);
		return chunks.size();
	}

	// ---- embedding cache -------------------------------------------------------------------------------------

	public Map<String, float[]> cachedEmbeddings(String model, Set<String> hashes) {
		Map<String, float[]> out = new HashMap<>();
		if (hashes.isEmpty()) {
			return out;
		}
		jdbc.query("SELECT text_hash, embedding::text AS v FROM embedding_cache WHERE embedding_model = ? AND text_hash = ANY (?)",
				rs -> {
					out.put(rs.getString("text_hash"), parseVector(rs.getString("v")));
				}, model, hashes.toArray(String[]::new));
		return out;
	}

	public void cacheEmbeddings(String model, Map<String, float[]> byHash) {
		if (byHash.isEmpty()) {
			return;
		}
		List<Object[]> rows = new ArrayList<>();
		Timestamp now = Timestamp.from(Instant.now());
		byHash.forEach((hash, v) -> rows.add(new Object[] { model, hash, toVectorLiteral(v), now }));
		jdbc.batchUpdate("""
				INSERT INTO embedding_cache (embedding_model, text_hash, embedding, created_at) VALUES (?, ?, ?::vector, ?)
				ON CONFLICT (embedding_model, text_hash) DO NOTHING""", rows);
	}

	// ---- runs and failures -------------------------------------------------------------------------------------

	public long startRun(String configJson) {
		return jdbc.queryForObject("""
				INSERT INTO ingestion_run (started_at, status, config) VALUES (?, 'RUNNING', ?::jsonb) RETURNING id""",
				Long.class, Timestamp.from(Instant.now()), configJson);
	}

	public void finishRun(long runId, IngestionService.Report r) {
		jdbc.update("""
				UPDATE ingestion_run SET finished_at = ?, status = ?, documents_total = ?, documents_ingested = ?,
				  documents_unchanged = ?, documents_removed = ?, documents_failed = ?, chunks_written = ?,
				  embeddings_computed = ?, embeddings_cached = ?, embedding_requests = ? WHERE id = ?""",
				Timestamp.from(Instant.now()), r.status(), r.total(), r.ingested(), r.unchanged(), r.removed(), r.failed(),
				r.chunksWritten(), r.embeddingsComputed(), r.embeddingsCached(), r.embeddingRequests(), runId);
	}

	public void recordFailure(long runId, String sourcePath, String stage, String message) {
		String msg = message == null ? "(no message)" : message.length() > 2000 ? message.substring(0, 2000) : message;
		jdbc.update("""
				INSERT INTO ingestion_failure (run_id, source_path, stage, error_message, occurred_at)
				VALUES (?, ?, ?, ?, ?)""", runId, sourcePath, stage, msg, Timestamp.from(Instant.now()));
	}

	// ---- vector literal helpers ----------------------------------------------------------------------------

	static String toVectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder(v.length * 10).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	static float[] parseVector(String literal) {
		String[] parts = literal.substring(1, literal.length() - 1).split(",");
		float[] v = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			v[i] = Float.parseFloat(parts[i]);
		}
		return v;
	}
}
