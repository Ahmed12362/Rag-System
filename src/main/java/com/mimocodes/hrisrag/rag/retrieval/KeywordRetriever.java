package com.mimocodes.hrisrag.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * Lexical retrieval with PostgreSQL full-text search over {@code document_chunk.search_vector} (a generated column,
 * stemmed with the 'english' or 'arabic' configuration per chunk language, GIN-indexed). Same access filter as the
 * vector retriever ({@link AccessSql}).
 * <ul>
 * <li>The question is split by script: Latin words and digits go to an 'english' query, Arabic words to an 'arabic'
 * query, so each part is stemmed and stop-word-filtered by the matching configuration.</li>
 * <li>Terms are OR-ed. {@code plainto_tsquery} ANDs every term, which matches nothing for a natural-language
 * question; ranking ({@code ts_rank_cd}) then rewards chunks that match more, and denser, terms.</li>
 * <li>Lexical search cannot cross languages; cross-lingual recall comes from the vector side of the hybrid.</li>
 * </ul>
 */
@Component
public class KeywordRetriever {

	private static final Pattern LATIN = Pattern.compile("[\\p{IsLatin}\\p{Nd}]+(?:[-:][\\p{IsLatin}\\p{Nd}]+)*");
	private static final Pattern ARABIC = Pattern.compile("\\p{IsArabic}+");

	private final JdbcTemplate jdbc;

	public KeywordRetriever(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The query text per configuration: [english part, arabic part]; either may be empty. */
	static String[] split(String question) {
		return new String[] { words(LATIN, question), words(ARABIC, question) };
	}

	private static String words(Pattern p, String text) {
		List<String> out = new ArrayList<>();
		Matcher m = p.matcher(text == null ? "" : text);
		while (m.find()) {
			out.add(m.group());
		}
		return String.join(" ", out);
	}

	public List<RetrievedChunk> retrieve(String question, UserContext user, int limit) {
		String[] parts = split(question);
		if (parts[0].isBlank() && parts[1].isBlank()) {
			return List.of();
		}
		Object[] access = AccessSql.args(user);
		// OR the terms: the text form of plainto_tsquery is "'a' & 'b'"; replacing & with | keeps the stemmed,
		// quoted lexemes. An empty part yields an empty tsquery, which || ignores; if both are empty, nothing matches.
		List<RetrievedChunk> rows = jdbc.query("""
				WITH q AS (
				  SELECT (CASE WHEN en = '' THEN ''::tsquery ELSE en::tsquery END)
				      || (CASE WHEN ar = '' THEN ''::tsquery ELSE ar::tsquery END) AS query
				  FROM (SELECT replace(plainto_tsquery('english', ?)::text, '&', '|') AS en,
				               replace(plainto_tsquery('arabic', ?)::text, '&', '|') AS ar) t
				)
				SELECT c.id, d.doc_key, d.version, d.title, c.section_path, c.language, d.status, d.access_level,
				       d.scope_department, c.content, ts_rank_cd(c.search_vector, q.query) AS score
				FROM q, document_chunk c JOIN source_document d ON d.id = c.document_id
				WHERE q.query <> ''::tsquery AND c.search_vector @@ q.query AND""" + AccessSql.FILTER + """

				ORDER BY score DESC, c.id
				LIMIT ?""", ChunkRows.MAPPER, parts[0], parts[1], access[0], access[1], limit);
		return ChunkRows.rank(rows);
	}
}
