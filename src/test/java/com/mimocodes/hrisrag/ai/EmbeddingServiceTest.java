package com.mimocodes.hrisrag.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.mimocodes.hrisrag.config.EmbeddingOptionsFactory;

class EmbeddingServiceTest {

	private final List<EmbeddingRequest> requests = new ArrayList<>();
	private final List<Long> sleeps = new ArrayList<>();

	private EmbeddingService service(Function<EmbeddingRequest, EmbeddingResponse> behaviour, int dims) {
		EmbeddingModel model = new EmbeddingModel() {
			@Override
			public EmbeddingResponse call(EmbeddingRequest request) {
				requests.add(request);
				return behaviour.apply(request);
			}

			@Override
			public float[] embed(Document document) {
				throw new UnsupportedOperationException();
			}
		};
		EmbeddingOptionsFactory options = (m, d, purpose) -> EmbeddingOptions.builder().model(m + ":" + purpose)
				.dimensions(d).build();
		return new EmbeddingService(model, options, new EmbeddingProperties("test-model", dims, 25, 0, 2, 100),
				sleeps::add);
	}

	private static EmbeddingResponse vectors(EmbeddingRequest r, int dims) {
		return new EmbeddingResponse(IntStream.range(0, r.getInstructions().size())
				.mapToObj(i -> new Embedding(new float[dims], i)).toList());
	}

	@Test
	void batchesDocumentsAndUsesDocumentTaskType() {
		var s = service(r -> vectors(r, 8), 8);
		var out = s.embedDocuments(IntStream.range(0, 55).mapToObj(i -> "text " + i).toList());

		assertThat(out).hasSize(55);
		assertThat(requests).extracting(r -> r.getInstructions().size()).containsExactly(25, 25, 5);
		assertThat(requests.getFirst().getOptions().getModel()).isEqualTo("test-model:DOCUMENT");
		assertThat(s.requestCount()).isEqualTo(3);
		assertThat(s.embeddedTextCount()).isEqualTo(55);
	}

	@Test
	void queriesUseQueryTaskType() {
		var s = service(r -> vectors(r, 8), 8);
		s.embedQuery("how many leave days?");
		assertThat(requests.getFirst().getOptions().getModel()).isEqualTo("test-model:QUERY");
	}

	@Test
	void wrongDimensionsAreRejected() {
		var s = service(r -> vectors(r, 4), 8);
		assertThatThrownBy(() -> s.embedDocuments(List.of("a"))).hasMessageContaining("4 dimensions");
	}

	@Test
	void transientErrorsAreRetriedWithBackoff() {
		int[] calls = { 0 };
		var s = service(r -> {
			if (calls[0]++ == 0) {
				throw new RuntimeException("503 UNAVAILABLE");
			}
			return vectors(r, 8);
		}, 8);
		assertThat(s.embedDocuments(List.of("a"))).hasSize(1);
		assertThat(sleeps).containsExactly(100L);
	}

	@Test
	void quotaExhaustionIsReportedDistinctly() {
		var daily = service(r -> {
			throw new RuntimeException("429 RESOURCE_EXHAUSTED {\"quotaId\":\"EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier\"}");
		}, 8);
		assertThatThrownBy(() -> daily.embedDocuments(List.of("a")))
				.isInstanceOf(EmbeddingService.EmbeddingQuotaExceededException.class)
				.hasMessageContaining("quotaId=EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier");

		var persistent = service(r -> {
			throw new RuntimeException("429 Too Many Requests");
		}, 8);
		assertThatThrownBy(() -> persistent.embedDocuments(List.of("a")))
				.isInstanceOf(EmbeddingService.EmbeddingQuotaExceededException.class);
		assertThat(requests).hasSize(1 + 3); // daily: 1 call; persistent: 1 + 2 retries
	}
}
