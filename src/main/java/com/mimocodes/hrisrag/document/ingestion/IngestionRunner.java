package com.mimocodes.hrisrag.document.ingestion;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.EmbeddingService;
import com.mimocodes.hrisrag.common.RunReports;

/**
 * Command-line ingestion, active only with {@code --hrisrag.ingest.run=true}. Prints start/finish banners, writes a
 * report to {@code hrisrag.ingest.report-dir}, then exits (0 = SUCCEEDED, 1 = PARTIAL/FAILED).
 */
@Component
@ConditionalOnProperty(prefix = "hrisrag.ingest", name = "run", havingValue = "true")
public class IngestionRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

	private final IngestionService service;
	private final IngestionProperties props;
	private final EmbeddingService embeddings;
	private final ChunkingProperties chunking;
	private final ConfigurableApplicationContext context;

	public IngestionRunner(IngestionService service, IngestionProperties props, EmbeddingService embeddings,
			ChunkingProperties chunking, ConfigurableApplicationContext context) {
		this.service = service;
		this.props = props;
		this.embeddings = embeddings;
		this.chunking = chunking;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			RunReports.banner(log, "DOCUMENT INGESTION STARTED", "corpus=" + Path.of(props.corpusDir()).toAbsolutePath()
					+ "\nembedding model=" + embeddings.modelName() + " (" + embeddings.dimensions() + " dims)"
					+ "\nchunking=" + chunking);
			IngestionService.Report r = service.ingest(Path.of(props.corpusDir()));
			StringBuilder sb = new StringBuilder("===== DOCUMENT INGESTION REPORT =====\n");
			sb.append(String.format("run_id=%d status=%s duration=%,d ms%n", r.runId(), r.status(), r.durationMs()));
			sb.append(String.format("documents: total=%d ingested(new/changed)=%d unchanged(skipped)=%d removed=%d "
					+ "failed=%d not_processed=%d%n", r.total(), r.ingested(), r.unchanged(), r.removed(), r.failed(),
					r.notProcessed()));
			sb.append(String.format("chunks_written=%d embeddings: computed=%d from_cache=%d provider_requests=%d%n",
					r.chunksWritten(), r.embeddingsComputed(), r.embeddingsCached(), r.embeddingRequests()));
			r.failures().forEach(f -> sb.append("FAILED ").append(f.stage()).append(' ').append(f.sourcePath())
					.append(": ").append(f.message()).append('\n'));
			if (r.notProcessed() > 0) {
				sb.append("Embedding quota exhausted: re-run the same command later to resume.\n");
			}
			Path report = RunReports.write(props.reportDir(), "ingestion", sb.toString());
			log.info("\n{}", sb);
			String result = r.status().equals("SUCCEEDED") ? "FINISHED: SUCCESS"
					: r.notProcessed() > 0 ? "STOPPED: EMBEDDING QUOTA EXHAUSTED" : "FINISHED WITH FAILURES";
			RunReports.banner(log, "DOCUMENT INGESTION " + result, String.format(
					"total=%d ingested=%d unchanged=%d removed=%d failed=%d not_processed=%d chunks=%d%nreport: %s",
					r.total(), r.ingested(), r.unchanged(), r.removed(), r.failed(), r.notProcessed(), r.chunksWritten(),
					report.toAbsolutePath()));
			code = r.status().equals("SUCCEEDED") ? 0 : 1;
		}
		catch (RuntimeException e) {
			log.error("Ingestion failed", e);
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(context, () -> exit));
	}
}
