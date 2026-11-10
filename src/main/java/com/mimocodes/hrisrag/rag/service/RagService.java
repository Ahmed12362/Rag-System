package com.mimocodes.hrisrag.rag.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.RagStrategy;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;

/** Resolves the strategy (registry of Spring beans), executes it, and persists the trace. */
@Service
public class RagService {

	private final Map<StrategyId, RagStrategy> strategies = new EnumMap<>(StrategyId.class);
	private final TraceStore traces;

	public RagService(List<RagStrategy> beans, TraceStore traces) {
		beans.forEach(s -> strategies.put(s.id(), s));
		this.traces = traces;
	}

	public Optional<RagStrategy> strategy(StrategyId id) {
		return Optional.ofNullable(strategies.get(id));
	}

	public boolean available(StrategyId id) {
		return strategies.containsKey(id);
	}

	public RagResult ask(StrategyId id, RagQuery query, RagSettings settings) {
		RagStrategy strategy = strategy(id).orElseThrow(
				() -> new IllegalArgumentException("strategy " + id + " is not available yet"));
		RagResult result = strategy.execute(query, settings);
		traces.save(query, result);
		return result;
	}
}
