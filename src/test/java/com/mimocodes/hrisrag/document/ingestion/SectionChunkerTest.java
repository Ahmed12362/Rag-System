package com.mimocodes.hrisrag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.IntSummaryStatistics;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.document.corpus.DocumentMetadata;
import com.mimocodes.hrisrag.document.ingestion.SectionChunker.Chunk;

class SectionChunkerTest {

	private final ChunkingProperties props = new ChunkingProperties(250, 400, 40, 40);
	private final SectionChunker chunker = new SectionChunker(props);

	private static DocumentMetadata md(String lang) {
		return new DocumentMetadata("T-" + lang.toUpperCase(), "1.0", "Annual Leave Policy", "LEAVE", "POLICY", lang,
				"ACTIVE", LocalDate.of(2026, 1, 1), null, null, "INTERNAL");
	}

	private static String words(int n, String word) {
		return (word + " ").repeat(n).strip() + ".";
	}

	@Test
	void oneChunkPerSectionWithContextHeaderAndCleanContent() {
		String body = "# Annual Leave Policy\n\n## 1. Purpose\nWhy this policy exists.\n\n## 2. Requesting leave\n"
				+ "Submit requests 14 days ahead.\n";
		List<Chunk> chunks = chunker.chunk(md("en"), body);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(1).sectionPath()).isEqualTo("Annual Leave Policy > 2. Requesting leave");
		assertThat(chunks.get(1).content()).isEqualTo("## 2. Requesting leave\n\nSubmit requests 14 days ahead.");
		assertThat(chunks.get(1).embeddingText())
				.isEqualTo("Annual Leave Policy > 2. Requesting leave\n\nSubmit requests 14 days ahead.");
		assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
		assertThat(chunks.get(0).contentHash()).hasSize(64).isNotEqualTo(chunks.get(1).contentHash());
	}

	@Test
	void longSectionIsSplitAtParagraphsWithOverlapAndSizeLimits() {
		// 4 paragraphs of ~100 words (~130 est. tokens each) = ~520 tokens > max 400 -> must split
		String section = String.join("\n\n", words(100, "alpha"), words(100, "beta"), words(100, "gamma"),
				words(100, "delta"));
		List<Chunk> chunks = chunker.chunk(md("en"), "# T\n\n## 1. Long\n" + section);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allMatch(c -> c.tokenCount() <= props.maxTokens() + props.overlapTokens());
		assertThat(chunks).allMatch(c -> c.sectionPath().endsWith("> 1. Long"));
		assertThat(String.join(" ", chunks.stream().map(Chunk::content).toList()))
				.contains("alpha", "beta", "gamma", "delta"); // nothing lost
	}

	@Test
	void overlapRepeatsTrailingSentencesWhenSplitting() {
		String p1 = "First idea sentence one. " + words(150, "x") + " Closing short sentence.";
		String p2 = words(150, "y");
		List<String> pieces = chunker.split(p1 + "\n\n" + p2 + "\n\n" + words(150, "z"), "en");

		assertThat(pieces).hasSizeGreaterThan(1);
		assertThat(pieces.get(1)).startsWith("Closing short sentence.");
	}

	@Test
	void tinyTailIsMergedBackIntoPreviousPiece() {
		List<String> pieces = chunker.split(words(180, "a") + "\n\n" + words(180, "b") + "\n\n" + "Tiny tail.", "en");
		assertThat(pieces.getLast()).endsWith("Tiny tail.");
		assertThat(pieces).noneMatch(p -> p.equals("Tiny tail."));
	}

	@Test
	void arabicTokenEstimateIsHigherPerWord() {
		assertThat(SectionChunker.estimateTokens("كلمة كلمة كلمة كلمة", "ar"))
				.isGreaterThan(SectionChunker.estimateTokens("word word word word", "en"));
	}

	@Test
	void realCorpusChunksDeterministicallyWithinLimits() {
		var loaded = new CorpusLoader().load(Path.of("corpus"));
		assertThat(loaded.failures()).isEmpty();
		assertThat(loaded.documents()).hasSize(227);

		int total = 0;
		IntSummaryStatistics tokens = new IntSummaryStatistics();
		for (var d : loaded.documents()) {
			List<Chunk> a = chunker.chunk(d.metadata(), d.body());
			List<Chunk> b = chunker.chunk(d.metadata(), d.body());
			assertThat(a).as(d.id() + " deterministic").isEqualTo(b);
			assertThat(a).as(d.id() + " has chunks").isNotEmpty();
			a.forEach(c -> tokens.accept(c.tokenCount()));
			total += a.size();
		}
		assertThat(tokens.getMax()).isLessThanOrEqualTo(props.maxTokens() + props.overlapTokens());
		System.out.printf("[CHUNKS] documents=227 chunks=%d tokens(est.) min=%d avg=%.0f max=%d%n", total,
				tokens.getMin(), tokens.getAverage(), tokens.getMax());
	}
}
