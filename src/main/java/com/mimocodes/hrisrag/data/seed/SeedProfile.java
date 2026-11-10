package com.mimocodes.hrisrag.data.seed;

/**
 * Predefined dataset sizes.
 * <ul>
 * <li>{@code TEST}: fast automated tests.</li>
 * <li>{@code STANDARD}: the specification's target scale; day-to-day development and benchmark runs.</li>
 * <li>{@code LARGE}: performance work (M10): realistic index sizes and tool latency under volume
 * (~1.2M attendance rows = 5,000 employees × ~240 workdays).</li>
 * </ul>
 */
public enum SeedProfile {

	TEST(new SeedCounts(3, 30, 20, 60, 20, 15, 5, 10)),
	STANDARD(new SeedCounts(10, 500, 10, 1_000, 500, 300, 50, 100)),
	LARGE(new SeedCounts(30, 5_000, 240, 20_000, 10_000, 5_000, 500, 1_000));

	private final SeedCounts counts;

	SeedProfile(SeedCounts counts) {
		this.counts = counts;
	}

	public SeedCounts counts() {
		return counts;
	}
}
