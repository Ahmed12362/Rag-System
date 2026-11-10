package com.mimocodes.hrisrag.data.seed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MD5 over the full, ordered contents of each HRIS table. Two databases with equal fingerprints hold identical data,
 * which is how reproducibility ("same seed → same data") is proven and recorded with each seed run.
 * Timestamps are rendered in UTC (SET LOCAL inside a read-only transaction), so the result does not depend on the
 * JVM or session time zone.
 */
@Component
public class DatasetFingerprint {

	private static final Map<String, String> ORDER_BY = Map.of(
			"employee_compensation", "employee_id",
			"meeting_invitation", "meeting_id, employee_id");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public DatasetFingerprint(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setReadOnly(true);
	}

	public Map<String, String> perTable() {
		return tx.execute(status -> {
			jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
			Map<String, String> result = new LinkedHashMap<>();
			for (String table : SeedWriter.TABLES_IN_INSERT_ORDER) {
				String order = ORDER_BY.getOrDefault(table, "id");
				result.put(table, jdbc.queryForObject(
						"SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY " + order + "), '')) FROM " + table + " t",
						String.class));
			}
			return result;
		});
	}

	/** One hash over all per-table hashes. */
	public String combined() {
		return jdbc.queryForObject("SELECT md5(?)", String.class, String.join(",", perTable().values()));
	}
}
