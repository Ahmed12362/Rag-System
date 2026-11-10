package com.mimocodes.hrisrag.document.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code hrisrag.ingest.*}: ingestion only runs when {@code run=true} is passed explicitly. */
@ConfigurationProperties(prefix = "hrisrag.ingest")
public record IngestionProperties(
		@DefaultValue("false") boolean run,
		@DefaultValue("corpus") String corpusDir,
		@DefaultValue("target/ingestion-reports") String reportDir) {
}
