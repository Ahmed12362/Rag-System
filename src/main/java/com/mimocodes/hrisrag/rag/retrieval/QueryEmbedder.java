package com.mimocodes.hrisrag.rag.retrieval;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.EmbeddingCache;
import com.mimocodes.hrisrag.ai.EmbeddingService;
import com.mimocodes.hrisrag.config.EmbeddingOptionsFactory.Purpose;
import com.mimocodes.hrisrag.rag.core.RagContract.ModelCall;

/** Query embeddings with a persistent cache (RETRIEVAL_QUERY): a repeated question costs no embedding quota. */
@Component
public class QueryEmbedder {

	public record QueryVector(float[] vector, ModelCall call) {
	}

	private final EmbeddingService embeddings;
	private final EmbeddingCache cache;

	public QueryEmbedder(EmbeddingService embeddings, EmbeddingCache cache) {
		this.embeddings = embeddings;
		this.cache = cache;
	}

	public QueryVector embed(String question) {
		String text = question.strip();
		String hash = EmbeddingCache.sha256(text);
		long start = System.nanoTime();
		Map<String, float[]> hit = cache.get(embeddings.modelName(), Purpose.QUERY, Set.of(hash));
		if (hit.containsKey(hash)) {
			return new QueryVector(hit.get(hash), new ModelCall("EMBED_QUERY", embeddings.modelName(),
					(System.nanoTime() - start) / 1_000_000, null, null, null, null, true));
		}
		float[] v = embeddings.embedQuery(text);
		cache.put(embeddings.modelName(), Purpose.QUERY, Map.of(hash, v));
		return new QueryVector(v, new ModelCall("EMBED_QUERY", embeddings.modelName(),
				(System.nanoTime() - start) / 1_000_000, null, null, null, null, false));
	}
}
