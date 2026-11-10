package com.mimocodes.hrisrag.data.seed;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reset-and-regenerate: truncate the HRIS tables, write a freshly generated dataset, validate it, all in ONE
 * transaction. If validation fails the transaction rolls back, so the previous data stays intact.
 */
@Service
public class SeedService {

	private static final Logger log = LoggerFactory.getLogger(SeedService.class);

	private final SyntheticDataGenerator generator;
	private final SeedWriter writer;
	private final SeedValidator validator;

	public SeedService(SyntheticDataGenerator generator, SeedWriter writer, SeedValidator validator) {
		this.generator = generator;
		this.writer = writer;
		this.validator = validator;
	}

	public record Result(SeedSpec spec, Map<String, Integer> rowCounts, long generateMs, Map<String, Long> writeMs,
			long validateMs, SeedValidator.Report report) {
		public long totalWriteMs() {
			return writeMs.values().stream().mapToLong(Long::longValue).sum();
		}
	}

	public static class SeedValidationException extends RuntimeException {
		private final transient SeedValidator.Report report;

		public SeedValidationException(SeedValidator.Report report) {
			super("Synthetic data validation failed:\n - " + String.join("\n - ", report.failures()));
			this.report = report;
		}

		public SeedValidator.Report report() {
			return report;
		}
	}

	@Transactional
	public Result reseed(SeedSpec spec) {
		log.info("Seeding HRIS data: profile={} seed={} referenceDate={} counts={}", spec.profile(), spec.seed(),
				spec.referenceDate(), spec.counts());

		long t0 = System.nanoTime();
		SeedDataset dataset = generator.generate(spec);
		long generateMs = (System.nanoTime() - t0) / 1_000_000;

		writer.truncate();
		Map<String, Long> writeMs = writer.write(dataset);

		long t1 = System.nanoTime();
		SeedValidator.Report report = validator.validate(spec, dataset.rowCounts());
		long validateMs = (System.nanoTime() - t1) / 1_000_000;

		if (!report.ok()) {
			throw new SeedValidationException(report); // rolls back: previous data is kept
		}
		Result result = new Result(spec, dataset.rowCounts(), generateMs, writeMs, validateMs, report);
		log.info("Seed complete: rows={} generate={}ms write={}ms validate={}ms", result.rowCounts(), generateMs,
				result.totalWriteMs(), validateMs);
		return result;
	}
}
