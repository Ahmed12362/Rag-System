package com.mimocodes.hrisrag.document.corpus;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.corpus.*}: corpus generation is an explicit, offline developer action and never runs on a normal start.
 *
 * @param generate             generate missing/invalid documents (selected by {@code only} or {@code pilot}), then exit
 * @param validateOnly         validate the committed corpus against the spec and manifest, then exit
 * @param pilot                select the pilot batch (first EN and AR document per group + one Egyptian FAQ)
 * @param only                 selectors: doc id (KEY@version), doc key, or group:NAME
 * @param requireComplete      validate-only: every planned document must exist
 * @param maxTransientRetries  retries for 429/5xx provider errors (exponential backoff)
 * @param maxFeedbackAttempts  regenerations with validator feedback after a failed draft
 */
@ConfigurationProperties(prefix = "hrisrag.corpus")
public record CorpusProperties(
		@DefaultValue("false") boolean generate,
		@DefaultValue("false") boolean validateOnly,
		@DefaultValue("false") boolean pilot,
		@DefaultValue List<String> only,
		@DefaultValue("false") boolean requireComplete,
		@DefaultValue("corpus-spec") String specDir,
		@DefaultValue("corpus") String outputDir,
		@DefaultValue("target/corpus-rejected") String rejectedDir,
		@DefaultValue("gemini-3.5-flash-lite") String model,
		@DefaultValue("0.3") double temperature,
		@DefaultValue("1500") long delayMs,
		@DefaultValue("5000") long initialBackoffMs,
		@DefaultValue("4") int maxTransientRetries,
		@DefaultValue("2") int maxFeedbackAttempts) {
}
