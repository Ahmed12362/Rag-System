package com.mimocodes.hrisrag.experiment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.benchmark.*} (command-line benchmark runs).
 *
 * @param cases  comma-separated case-id patterns, {@code *} as wildcard (e.g. {@code SEC-*,FACT-EN-0*}); blank = all
 * @param limit  maximum number of selected cases; 0 = no limit. The default (10) protects the free-tier quota: a full
 *               run must be requested explicitly with {@code --hrisrag.benchmark.limit=0}
 * @param resume experiment id to continue; its stored filter and limit are reused and finished cases are skipped
 * @param pause  wait after each question that made a chat call (rate limiting)
 * @param compareWith experiment id whose (first) run is printed side by side with this run in the report; only the
 *                    cases present in BOTH runs are compared
 */
@ConfigurationProperties(prefix = "hrisrag.benchmark")
public record BenchmarkProperties(
		@DefaultValue("false") boolean run,
		@DefaultValue("benchmark/hris-bench-v1.yaml") String datasetFile,
		@DefaultValue("corpus-spec") String corpusSpecDir,
		@DefaultValue("") String cases,
		@DefaultValue("10") int limit,
		Long resume,
		@DefaultValue("NAIVE") String strategy,
		@DefaultValue("4s") Duration pause,
		@DefaultValue("reports/benchmark") String reportDir,
		Long compareWith) {
}
