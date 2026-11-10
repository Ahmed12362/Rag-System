package com.mimocodes.hrisrag.document.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.ingest.chunking.*}, in ESTIMATED tokens (see {@link SectionChunker#estimateTokens}).
 *
 * @param targetTokens  preferred size when a long section must be split
 * @param maxTokens     a section up to this size stays one chunk
 * @param minTokens     a split-off tail piece smaller than this is merged back into the previous piece
 * @param overlapTokens trailing context repeated at the start of the next piece when a section is split
 */
@ConfigurationProperties(prefix = "hrisrag.ingest.chunking")
public record ChunkingProperties(
		@DefaultValue("250") int targetTokens,
		@DefaultValue("400") int maxTokens,
		@DefaultValue("40") int minTokens,
		@DefaultValue("40") int overlapTokens) {

	public ChunkingProperties {
		if (targetTokens <= 0 || maxTokens < targetTokens || minTokens < 0 || overlapTokens < 0
				|| overlapTokens >= targetTokens) {
			throw new IllegalArgumentException("invalid chunking settings");
		}
	}
}
