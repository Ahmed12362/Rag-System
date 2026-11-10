package com.mimocodes.hrisrag.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mimocodes.hrisrag.config.EmbeddingOptionsFactory.Purpose;

/**
 * (model, purpose, text hash) → vector. Identical text is never embedded twice for the same purpose. Essential on the
 * free tier, which allows ~1,000 embedded texts per day for documents and queries together (decision D32).
 */
@Repository
public class EmbeddingCache {

	private final JdbcTemplate jdbc;

	public EmbeddingCache(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, float[]> get(String model, Purpose purpose, Set<String> hashes) {
		Map<String, float[]> out = new HashMap<>();
		if (hashes.isEmpty()) {
			return out;
		}
		jdbc.query("""
				SELECT text_hash, embedding::text AS v FROM embedding_cache
				WHERE embedding_model = ? AND purpose = ? AND text_hash = ANY (?)""",
				rs -> {
					out.put(rs.getString("text_hash"), parseVector(rs.getString("v")));
				}, model, purpose.name(), hashes.toArray(String[]::new));
		return out;
	}

	public void put(String model, Purpose purpose, Map<String, float[]> byHash) {
		if (byHash.isEmpty()) {
			return;
		}
		List<Object[]> rows = new ArrayList<>();
		Timestamp now = Timestamp.from(Instant.now());
		byHash.forEach((hash, v) -> rows.add(new Object[] { model, purpose.name(), hash, toVectorLiteral(v), now }));
		jdbc.batchUpdate("""
				INSERT INTO embedding_cache (embedding_model, purpose, text_hash, embedding, created_at)
				VALUES (?, ?, ?, ?::vector, ?) ON CONFLICT (embedding_model, purpose, text_hash) DO NOTHING""", rows);
	}

	public static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String toVectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder(v.length * 10).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	public static float[] parseVector(String literal) {
		String[] parts = literal.substring(1, literal.length() - 1).split(",");
		float[] v = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			v[i] = Float.parseFloat(parts[i]);
		}
		return v;
	}
}
