package com.mimocodes.hrisrag.rag.retrieval;

/** Exposes package-private helpers of the retrieval package to tests elsewhere. */
public final class KeywordRetrieverTestAccess {

	private KeywordRetrieverTestAccess() {
	}

	public static String[] split(String question) {
		return KeywordRetriever.split(question);
	}
}
