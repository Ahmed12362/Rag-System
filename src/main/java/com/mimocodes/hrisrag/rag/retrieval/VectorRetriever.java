package com.mimocodes.hrisrag.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mimocodes.hrisrag.ai.EmbeddingCache;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * Cosine top-K over document_chunk with the security filters INSIDE the SQL ({@link AccessSql}, decision D12): only
 * ACTIVE versions, only access levels the user may see, only company-wide or the user's own department. Restricted text
 * therefore never reaches application memory or the LLM.
 * <p>
 * HNSW applies WHERE filters after the index scan, which can return fewer than K rows when a filter is selective (risk
 * R5). {@code hnsw.iterative_scan = relaxed_order} (pgvector 0.8) keeps scanning until K rows pass; results are then
 * re-sorted by score because relaxed order may be slightly out of order.
 */
@Component
public class VectorRetriever {

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public VectorRetriever(JdbcTemplate jdbc, PlatformTransactionManager tm) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(tm);
		this.tx.setReadOnly(true);
	}

	public List<RetrievedChunk> retrieve(float[] queryVector, UserContext user, int topK) {
		String q = EmbeddingCache.toVectorLiteral(queryVector);
		Object[] access = AccessSql.args(user);
		List<RetrievedChunk> rows = tx.execute(status -> {
			jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
			return jdbc.query("""
					SELECT c.id, d.doc_key, d.version, d.title, c.section_path, c.language, d.status, d.access_level,
					       d.scope_department, c.content, 1 - (c.embedding <=> ?::vector) AS score
					FROM document_chunk c JOIN source_document d ON d.id = c.document_id
					WHERE """ + AccessSql.FILTER + """

					ORDER BY c.embedding <=> ?::vector
					LIMIT ?""", ChunkRows.MAPPER, q, access[0], access[1], q, topK);
		});
		List<RetrievedChunk> sorted = new ArrayList<>(rows);
		sorted.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
				.thenComparingLong(RetrievedChunk::chunkId)); // deterministic tie-break
		return ChunkRows.rank(sorted);
	}
}
