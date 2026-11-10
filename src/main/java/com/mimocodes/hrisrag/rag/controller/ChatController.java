package com.mimocodes.hrisrag.rag.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.service.RagService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Chat / playground API. The caller's identity is NOT part of the request: until Spring Security arrives (M6) it is the
 * server-side dev user from configuration.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ChatController {

	public record ChatRequest(
			@NotBlank @Size(max = 2000) String question,
			@Pattern(regexp = "(?i)NAIVE|HYBRID|AGENTIC", message = "must be NAIVE, HYBRID or AGENTIC") String strategy,
			@Size(max = 64) String conversationId) {
	}

	public record StrategyInfo(StrategyId id, boolean available, int defaultTopK, String chatModel) {
	}

	private final RagService rag;
	private final RagProperties props;

	public ChatController(RagService rag, RagProperties props) {
		this.rag = rag;
		this.props = props;
	}

	@PostMapping("/chat")
	public RagResult chat(@Valid @RequestBody ChatRequest request) {
		StrategyId id = request.strategy() == null ? StrategyId.NAIVE
				: StrategyId.valueOf(request.strategy().toUpperCase(Locale.ROOT));
		if (!rag.available(id)) {
			throw new StrategyUnavailableException(id);
		}
		return rag.ask(id, new RagQuery(request.question(), request.conversationId(), props.devUserContext()),
				props.defaultSettings());
	}

	@GetMapping("/strategies")
	public List<StrategyInfo> strategies() {
		return Arrays.stream(StrategyId.values())
				.map(s -> new StrategyInfo(s, rag.available(s), props.topK(), props.chatModel())).toList();
	}

	static class StrategyUnavailableException extends RuntimeException {
		StrategyUnavailableException(StrategyId id) {
			super("Strategy " + id + " is not available yet");
		}
	}

	@ExceptionHandler(StrategyUnavailableException.class)
	ProblemDetail unavailable(StrategyUnavailableException e) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		pd.setTitle("Strategy not available");
		return pd;
	}
}
