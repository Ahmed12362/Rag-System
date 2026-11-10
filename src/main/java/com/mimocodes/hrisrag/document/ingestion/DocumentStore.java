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

	private static String toVectorLiteral(float[] v) {
		return com.mimocodes.hrisrag.ai.EmbeddingCache.toVectorLiteral(v);
	}
}
