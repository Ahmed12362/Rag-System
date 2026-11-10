package com.mimocodes.hrisrag.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mimocodes.hrisrag.config.EmbeddingOptionsFactory;
import com.mimocodes.hrisrag.config.EmbeddingOptionsFactory.Purpose;

/**
 * Thin gateway over Spring AI's {@link EmbeddingModel} (decision D8): batching, document-vs-query task type, dimension
 * check against the database schema, retry with backoff, and call counting. Used by ingestion (documents) and, from
 * M5, retrieval (queries).
 */
@Service
public class EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis);
	}

	/** The provider's quota is exhausted (daily limit, or rate limiting that did not clear after all retries). */
	public static class EmbeddingQuotaExceededException extends RuntimeException {
		public EmbeddingQuotaExceededException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private final EmbeddingModel model;
	private final EmbeddingOptionsFactory optionsFactory;
	private final EmbeddingProperties props;
	private final Sleeper sleeper;
	private final AtomicLong requests = new AtomicLong();
	private final AtomicLong embeddedTexts = new AtomicLong();

	@Autowired
	public EmbeddingService(EmbeddingModel model, EmbeddingOptionsFactory optionsFactory, EmbeddingProperties props) {
		this(model, optionsFactory, props, EmbeddingService::sleepQuietly);
	}

	public EmbeddingService(EmbeddingModel model, EmbeddingOptionsFactory optionsFactory, EmbeddingProperties props,
			Sleeper sleeper) {
		this.model = model;
		this.optionsFactory = optionsFactory;
		this.props = props;
		this.sleeper = sleeper;
	}

	public String modelName() {
		return props.model();
	}

	public int dimensions() {
		return props.dimensions();
	}

	/** Successful provider requests so far (batches, not texts). */
	public long requestCount() {
		return requests.get();
	}

	public long embeddedTextCount() {
		return embeddedTexts.get();
	}

	/** Embeds document chunks (RETRIEVAL_DOCUMENT), in batches. Result order matches input order. */
	public List<float[]> embedDocuments(List<String> texts) {
		List<float[]> out = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += props.batchSize()) {
			List<String> batch = texts.subList(from, Math.min(texts.size(), from + props.batchSize()));
			out.addAll(embedBatch(batch, Purpose.DOCUMENT));
		}
		return out;
	}

	/** Embeds a user question (RETRIEVAL_QUERY). */
	public float[] embedQuery(String question) {
		return embedBatch(List.of(question), Purpose.QUERY).getFirst();
	}

	private List<float[]> embedBatch(List<String> batch, Purpose purpose) {
		EmbeddingRequest request = new EmbeddingRequest(List.copyOf(batch),
				optionsFactory.create(props.model(), props.dimensions(), purpose));
		long backoff = props.initialBackoffMs();
		for (int attempt = 1;; attempt++) {
			if (props.delayMs() > 0) {
				sleeper.sleep(props.delayMs());
			}
			try {
				EmbeddingResponse response = model.call(request);
				List<float[]> vectors = response.getResults().stream().map(Embedding::getOutput).toList();
				if (vectors.size() != batch.size()) {
					throw new IllegalStateException(
							"provider returned " + vectors.size() + " embeddings for " + batch.size() + " texts");
				}
				for (float[] v : vectors) {
					if (v.length != props.dimensions()) {
						throw new IllegalStateException("embedding has " + v.length + " dimensions, schema expects "
								+ props.dimensions() + " (model " + props.model() + ")");
					}
				}
				requests.incrementAndGet();
				embeddedTexts.addAndGet(batch.size());
				return vectors;
			}
			catch (IllegalStateException e) {
				throw e;
			}
			catch (RuntimeException e) {
				if (ProviderErrors.isDailyQuota(e)) {
					throw new EmbeddingQuotaExceededException("daily embedding quota exhausted; provider said: "
							+ ProviderErrors.summary(e), e);
				}
				if (!ProviderErrors.isTransient(e)) {
					throw e;
				}
				if (attempt > props.maxRetries()) {
					if (ProviderErrors.isRateLimit(e)) {
						throw new EmbeddingQuotaExceededException("embedding rate limit did not clear after "
								+ props.maxRetries() + " retries; provider said: " + ProviderErrors.summary(e), e);
					}
					throw e;
				}
				log.warn("embedding batch of {} failed (attempt {}), retrying in {} ms: {}", batch.size(), attempt, backoff,
						e.getMessage());
				sleeper.sleep(backoff);
				backoff *= 2;
			}
		}
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", e);
		}
	}
}
