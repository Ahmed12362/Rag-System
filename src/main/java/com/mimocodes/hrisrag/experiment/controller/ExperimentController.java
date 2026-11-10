package com.mimocodes.hrisrag.experiment.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mimocodes.hrisrag.evaluation.MetricAggregator.Aggregate;
import com.mimocodes.hrisrag.experiment.ExperimentStore;
import com.mimocodes.hrisrag.experiment.ExperimentStore.ExperimentRow;

/**
 * Read-only experiment results (HR_ADMIN only, see SecurityConfig). Launching runs from the API comes in M9; for now
 * runs are started from the command line ({@code --hrisrag.benchmark.run=true}).
 */
@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

	public record RunView(long runId, String strategy, int trial, List<Aggregate> aggregates,
			List<Map<String, Object>> questions) {
	}

	public record ExperimentView(long id, String dataset, String datasetVersion, String status, String caseFilter,
			int caseLimit, Instant startedAt, Instant finishedAt, String stopReason, int results, List<RunView> runs) {
	}

	private final ExperimentStore store;

	public ExperimentController(ExperimentStore store) {
		this.store = store;
	}

	@GetMapping
	public List<ExperimentView> list() {
		return store.recentExperiments(50).stream().map(e -> view(e, List.of())).toList();
	}

	@GetMapping("/{id}")
	public ExperimentView get(@PathVariable long id) {
		ExperimentRow e = store.experiment(id).orElseThrow(() -> new NotFound(id));
		List<RunView> runs = store.runs(id).stream().map(r -> {
			long runId = ((Number) r.get("id")).longValue();
			return new RunView(runId, (String) r.get("strategy"), ((Number) r.get("trial")).intValue(),
					store.aggregates(runId), store.resultSummaries(runId));
		}).toList();
		return view(e, runs);
	}

	private static ExperimentView view(ExperimentRow e, List<RunView> runs) {
		return new ExperimentView(e.id(), e.datasetName(), e.datasetVersion(), e.status(), e.caseFilter(), e.caseLimit(),
				e.startedAt(), e.finishedAt(), e.stopReason(), e.results(), runs);
	}

	static class NotFound extends RuntimeException {
		NotFound(long id) {
			super("Experiment " + id + " not found");
		}
	}

	@ExceptionHandler(NotFound.class)
	ProblemDetail notFound(NotFound e) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		pd.setTitle("Not found");
		return pd;
	}
}
