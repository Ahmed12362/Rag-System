package com.mimocodes.hrisrag.rag.retrieval;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;

/** Row mapping and 1-based ranking shared by the retrievers (column names from their SELECT lists). */
final class ChunkRows {

	static final RowMapper<RetrievedChunk> MAPPER = (rs, i) -> new RetrievedChunk(rs.getLong("id"),
			rs.getString("doc_key"), rs.getString("version"), rs.getString("title"), rs.getString("section_path"),
			rs.getString("language"), rs.getString("status"), rs.getString("access_level"),
			rs.getString("scope_department"), rs.getString("content"), rs.getDouble("score"), 0);

	private ChunkRows() {
	}

	/** Assigns ranks 1..n in list order. */
	static List<RetrievedChunk> rank(List<RetrievedChunk> ordered) {
		List<RetrievedChunk> ranked = new ArrayList<>(ordered.size());
		for (int i = 0; i < ordered.size(); i++) {
			RetrievedChunk c = ordered.get(i);
			ranked.add(c.ranked(c.score(), i + 1, c.vectorRank(), c.keywordRank()));
		}
		return ranked;
	}
}
