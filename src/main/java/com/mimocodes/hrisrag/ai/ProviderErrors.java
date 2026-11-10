package com.mimocodes.hrisrag.ai;

import java.util.Locale;

/** Classifies model-provider exceptions (walks the cause chain). Shared by corpus generation and embeddings. */
public final class ProviderErrors {

	private ProviderErrors() {
	}

	/** 429 / 5xx style failures that are worth retrying. */
	public static boolean isTransient(Throwable e) {
		return anyMessage(e, "429", "500", "502", "503", "504", "unavailable", "overloaded", "resource_exhausted",
				"high demand", "timeout", "timed out", "quota");
	}

	public static boolean isRateLimit(Throwable e) {
		return anyMessage(e, "429", "resource_exhausted", "quota");
	}

	/** Google reports daily free-tier limits with quota ids such as "GenerateRequestsPerDayPerProjectPerModel". */
	public static boolean isDailyQuota(Throwable e) {
		return anyMessage(e, "perday", "per day", "requests per day");
	}

	/**
	 * Evidence for reports: the provider's quota id (e.g. "EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier")
	 * if present, otherwise the first line of the deepest message, truncated.
	 */
	public static String summary(Throwable e) {
		String deepest = null;
		for (Throwable t = e; t != null; t = t.getCause()) {
			String m = t.getMessage();
			if (m == null) {
				continue;
			}
			java.util.regex.Matcher q = java.util.regex.Pattern.compile("\"quotaId\"\\s*:\\s*\"([^\"]+)\"").matcher(m);
			if (q.find()) {
				return "quotaId=" + q.group(1);
			}
			deepest = m;
		}
		if (deepest == null) {
			return e.getClass().getSimpleName();
		}
		String first = deepest.strip().split("\\R", 2)[0];
		return first.length() > 300 ? first.substring(0, 300) + "..." : first;
	}

	private static boolean anyMessage(Throwable e, String... needles) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
			for (String n : needles) {
				if (m.contains(n)) {
					return true;
				}
			}
		}
		return false;
	}
}
