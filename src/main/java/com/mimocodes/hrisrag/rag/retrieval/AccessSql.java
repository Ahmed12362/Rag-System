package com.mimocodes.hrisrag.rag.retrieval;

import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * The document access filter shared by EVERY retriever (decision D12), so vector and keyword search cannot drift apart:
 * only ACTIVE versions, only access levels the user may read, only company-wide or the user's own department.
 * Expects {@code source_document} aliased as {@code d}. {@link com.mimocodes.hrisrag.rag.guardrail.AccessPolicy}
 * re-checks the same rules in Java before any context is built.
 */
public final class AccessSql {

	/**
	 * Use after WHERE or AND; binds {@link #args(UserContext)} in order. A plain string padded with spaces (text blocks
	 * strip trailing whitespace, which would glue it to the preceding keyword).
	 */
	public static final String FILTER = " d.status = 'ACTIVE' AND d.access_level = ANY (?)"
			+ " AND (d.scope_department IS NULL OR d.scope_department = ?) ";

	private AccessSql() {
	}

	public static Object[] args(UserContext user) {
		return new Object[] { user.visibleAccessLevels().toArray(String[]::new), user.department() };
	}
}
