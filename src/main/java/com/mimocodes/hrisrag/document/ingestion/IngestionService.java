package com.mimocodes.hrisrag.document.ingestion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mimocodes.hrisrag.ai.EmbeddingService;
import com.mimocodes.hrisrag.ai.EmbeddingService.EmbeddingQuotaExceededException;
import com.mimocodes.hrisrag.document.ingestion.CorpusLoader.LoadedDocument;
import com.mimocodes.hrisrag.document.ingestion.SectionChunker.Chunk;

import tools.jackson.databind.json.JsonMapper;

/**
 * Incremental ingestion: load → chunk → embed → store, one transaction per document.
 * <ul>
 * <li>Unchanged documents (same file hash and same embedding model) are skipped: no chunking, no embedding call.</li>
 * <li>Changed or new documents are re-chunked; chunk embeddings come from the cache when the identical text was
 * embedded before with the same model, so only genuinely new text costs an API call.</li>
 * <li>Documents no longer in the corpus are deleted.</li>
 * <li>Failures are recorded per document (stage + message) and never stop the run, except an exhausted embedding
 * quota, which stops cleanly; the next run resumes because finished documents are skipped.</li>
 * </ul>
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	public record Failure(String sourcePath, String stage, String message) {
	}

	public record Report(long runId, String status, int total, int ingested, int unchanged, int removed, int failed,
			int notProcessed, int chunksWritten, int embeddingsComputed, int embeddingsCached, long embeddingRequests,
			long durationMs, List<Failure> failures) {
	}

	private final CorpusLoader loader;
	private final SectionChunker chunker;
	private final EmbeddingService embeddings;
	private final DocumentStore store;
	private final TransactionTemplate tx;
	private final ChunkingProperties chunking;

	public IngestionService(CorpusLoader loader, SectionChunker chunker, EmbeddingService embeddings, DocumentStore store,
			PlatformTransactionManager transactionManager, ChunkingProperties chunking) {
		this.loader = loader;
		this.chunker = chunker;
		this.embeddings = embeddings;
		this.store = store;
		this.tx = new TransactionTemplate(transactionManager);
		this.chunking = chunking;
	}

	public Report ingest(Path corpusRoot) {
		long started = System.nanoTime();
		long requestsBefore = embeddings.requestCount();
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("chunking", chunking);
		config.put("embeddingModel", embeddings.modelName());
		config.put("dimensions", embeddings.dimensions());
		config.put("corpus", corpusRoot.toString());
		long runId = store.startRun(JsonMapper.builder().build().writeValueAsString(config));

		CorpusLoader.Result loaded = loader.load(corpusRoot);
		List<Failure> failures = new ArrayList<>();
		loaded.failures().forEach(f -> failures.add(new Failure(f.relativePath(), f.stage(), f.message())));

		Map<String, DocumentStore.StoredDocument> stored = store.storedDocuments();
		Set<String> inCorpus = loaded.documents().stream().map(LoadedDocument::id).collect(Collectors.toSet());

		// 1. Remove documents that left the corpus (frees their ACTIVE slot before new versions are inserted).
		int removed = 0;
		for (DocumentStore.StoredDocument s : stored.values()) {
			if (!inCorpus.contains(s.key())) {
				tx.executeWithoutResult(t -> store.delete(s.id()));
				removed++;
				log.info("removed {} (no longer in the corpus)", s.key());
			}
		}

		// 2. Non-ACTIVE versions first, so a version change never violates "one ACTIVE version per doc_key".
		List<LoadedDocument> ordered = new ArrayList<>(loaded.documents());
		ordered.sort(Comparator.comparing((LoadedDocument d) -> "ACTIVE".equals(d.metadata().status()))
				.thenComparing(LoadedDocument::relativePath));

		int ingested = 0, unchanged = 0, chunksWritten = 0, computed = 0, cached = 0, notProcessed = 0;
		boolean quotaStop = false;
		for (LoadedDocument doc : ordered) {
			if (quotaStop) {
				notProcessed++;
				continue;
			}
			DocumentStore.StoredDocument existing = stored.get(doc.id());
			if (existing != null && existing.contentHash().equals(doc.contentHash())
					&& existing.embeddingModels().equals(Set.of(embeddings.modelName()))) {
				unchanged++;
				continue;
			}
			String stage = "CHUNK";
			try {
				List<Chunk> chunks = chunker.chunk(doc.metadata(), doc.body());
				if (chunks.isEmpty()) {
					throw new IllegalStateException("document produced no chunks");
				}
				stage = "EMBED";
				Set<String> hashes = chunks.stream().map(Chunk::contentHash).collect(Collectors.toSet());
				Map<String, float[]> vectorsByHash = new LinkedHashMap<>(store.cachedEmbeddings(embeddings.modelName(), hashes));
				int hits = vectorsByHash.size();
				List<Chunk> missing = chunks.stream().filter(c -> !vectorsByHash.containsKey(c.contentHash()))
						.collect(Collectors.toMap(Chunk::contentHash, c -> c, (a, b) -> a, LinkedHashMap::new))
						.values().stream().toList();
				Map<String, float[]> fresh = new LinkedHashMap<>();
				if (!missing.isEmpty()) {
					List<float[]> vectors = embeddings.embedDocuments(missing.stream().map(Chunk::embeddingText).toList());
					for (int i = 0; i < missing.size(); i++) {
						fresh.put(missing.get(i).contentHash(), vectors.get(i));
					}
					vectorsByHash.putAll(fresh);
				}
				stage = "STORE";
				List<float[]> orderedVectors = chunks.stream().map(c -> vectorsByHash.get(c.contentHash())).toList();
				int written = tx.execute(t -> {
					store.cacheEmbeddings(embeddings.modelName(), fresh);
					return store.replace(doc, chunks, orderedVectors, embeddings.modelName());
				});
				chunksWritten += written;
				computed += fresh.size();
				cached += hits;
				ingested++;
			}
			catch (EmbeddingQuotaExceededException e) {
				failures.add(new Failure(doc.relativePath(), "EMBED", e.getMessage()));
				log.error("Embedding quota exhausted at {}: stopping; re-run later to resume.", doc.id());
				quotaStop = true;
			}
			catch (RuntimeException e) {
				failures.add(new Failure(doc.relativePath(), stage, String.valueOf(e.getMessage())));
				log.error("{} failed at stage {}: {}", doc.id(), stage, e.getMessage());
			}
		}

		failures.forEach(f -> store.recordFailure(runId, f.sourcePath(), f.stage(), f.message()));
		int failed = failures.size();
		String status = failed == 0 && notProcessed == 0 ? "SUCCEEDED" : ingested + unchanged > 0 ? "PARTIAL" : "FAILED";
		Report report = new Report(runId, status, loaded.documents().size() + loaded.failures().size(), ingested,
				unchanged, removed, failed, notProcessed, chunksWritten, computed, cached,
				embeddings.requestCount() - requestsBefore, (System.nanoTime() - started) / 1_000_000, List.copyOf(failures));
		store.finishRun(runId, report);
		return report;
	}
}
