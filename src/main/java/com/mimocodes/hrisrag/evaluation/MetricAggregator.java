package com.mimocodes.hrisrag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates per-question metrics by slice: ALL, SCOPE (RAG_ONLY / TOOLS_REQUIRED), LANGUAGE and CATEGORY.
 * Rate metrics are averaged over the cases where they apply; {@code _count} metrics are summed; {@code latency_ms}
 * becomes mean, p50 and p95. Each aggregate carries its sample size.
 */
public final class MetricAggregator {

	public record Scored(String caseKey, String category, String language, String scope, Map<String, Double> metrics) {
	}

	public record Aggregate(String metric, String sliceType, String sliceValue, double value, int sampleSize) {
	}

	private MetricAggregator() {
	}

	public static List<Aggregate> aggregate(List<Scored> cases) {
		List<Aggregate> out = new ArrayList<>();
		out.addAll(slice("ALL", "ALL", cases));
		for (String type : List.of("SCOPE", "LANGUAGE", "CATEGORY")) {
			Map<String, List<Scored>> groups = new LinkedHashMap<>();
			for (Scored s : cases) {
				String key = switch (type) {
					case "SCOPE" -> s.scope();
					case "LANGUAGE" -> s.language();
					default -> s.category();
				};
				groups.computeIfAbsent(key, x -> new ArrayList<>()).add(s);
			}
			groups.forEach((value, group) -> out.addAll(slice(type, value, group)));
		}
		return out;
	}

	private static List<Aggregate> slice(String type, String value, List<Scored> cases) {
		Set<String> names = new LinkedHashSet<>();
		cases.forEach(c -> names.addAll(c.metrics().keySet()));
		List<Aggregate> out = new ArrayList<>();
		for (String name : names) {
			List<Double> values = cases.stream().map(c -> c.metrics().get(name)).filter(v -> v != null).toList();
			if (values.isEmpty()) {
				continue;
			}
			int n = values.size();
			if (name.endsWith("_count")) {
				out.add(new Aggregate(name.substring(0, name.length() - "_count".length()) + "_total", type, value,
						values.stream().mapToDouble(Double::doubleValue).sum(), n));
			}
			else if (name.equals("latency_ms")) {
				List<Double> sorted = values.stream().sorted().toList();
				out.add(new Aggregate("latency_mean_ms", type, value, mean(values), n));
				out.add(new Aggregate("latency_p50_ms", type, value, percentile(sorted, 50), n));
				out.add(new Aggregate("latency_p95_ms", type, value, percentile(sorted, 95), n));
			}
			else {
				out.add(new Aggregate(name, type, value, mean(values), n));
			}
		}
		return out;
	}

	private static double mean(List<Double> v) {
		return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
	}

	/** Nearest-rank percentile on a sorted list. */
	static double percentile(List<Double> sorted, int p) {
		int rank = (int) Math.ceil(p / 100.0 * sorted.size());
		return sorted.get(Math.max(0, rank - 1));
	}
}
