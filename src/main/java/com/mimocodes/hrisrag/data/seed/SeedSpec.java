package com.mimocodes.hrisrag.data.seed;

import java.time.LocalDate;

/**
 * Everything that determines a synthetic dataset. Same spec → byte-identical data.
 *
 * @param seed          master random seed
 * @param profile       profile the counts came from (recorded for traceability)
 * @param referenceDate the dataset's "today". All dates are generated relative to it, never to the wall clock.
 * @param counts        effective counts (profile defaults plus any overrides)
 */
public record SeedSpec(long seed, SeedProfile profile, LocalDate referenceDate, SeedCounts counts) {

	public static final LocalDate DEFAULT_REFERENCE_DATE = LocalDate.of(2026, 11, 1);

	public static SeedSpec of(long seed, SeedProfile profile) {
		return new SeedSpec(seed, profile, DEFAULT_REFERENCE_DATE, profile.counts());
	}

	/** The leave year whose balances are generated (the reference date's year). */
	public int leaveYear() {
		return referenceDate.getYear();
	}
}
