package com.mimocodes.hrisrag.document.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.document.corpus.DocumentMetadata;
import com.mimocodes.hrisrag.document.corpus.TextChecks;

/**
 * Structure-aware chunking (Task 4.1 decision): one chunk per {@code ##} section, because a section holds one policy rule
 * and gives a precise citation target. A section larger than {@code maxTokens} is split at paragraph, then line, then
 * sentence boundaries into pieces of about {@code targetTokens}, with {@code overlapTokens} of trailing context repeated.
 * Tiny split-off tails are merged back. Whole small sections are kept as they are (precise citations over uniform size).
 * <p>
 * Each chunk's embedding text is prefixed with "Title > Section" so a heading such as "4. Exceptions" is not ambiguous
 * on its own; the stored content stays clean. Deterministic: same input, same chunks.
 */
@Component
public class SectionChunker {

	public record Chunk(int index, String sectionPath, String content, String embeddingText, String contentHash,
			int tokenCount) {
	}

	private record Section(String heading, String text) {
	}

	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?؟])\\s+");
	/** Rough tokens per word; Arabic tokenizes into more tokens per word than English. These are estimates. */
	static final double TOKENS_PER_WORD_EN = 1.3;
	static final double TOKENS_PER_WORD_AR = 2.0;

	private final ChunkingProperties props;

	public SectionChunker(ChunkingProperties props) {
		this.props = props;
	}

	public List<Chunk> chunk(DocumentMetadata md, String markdownBody) {
		String language = md.language();
		List<Section> sections = sections(md.title(), markdownBody);
		List<Chunk> chunks = new ArrayList<>();
		for (Section s : sections) {
			for (String piece : split(s.text(), language)) {
				String path = s.heading() == null ? md.title() : md.title() + " > " + s.heading();
				String content = (s.heading() == null ? "" : "## " + s.heading() + "\n\n") + piece;
				String embeddingText = path + "\n\n" + piece;
				chunks.add(new Chunk(chunks.size(), path, content, embeddingText, sha256(embeddingText),
						Math.max(1, estimateTokens(content, language))));
			}
		}
		return chunks;
	}

	/** Splits the Markdown body into (heading, text) sections; text before the first heading becomes a headless section. */
	private static List<Section> sections(String title, String body) {
		List<Section> out = new ArrayList<>();
		String heading = null;
		StringBuilder text = new StringBuilder();
		for (String line : body.replace("\r\n", "\n").split("\n")) {
			if (line.startsWith("# ")) {
				continue; // document title: carried in metadata and the context header
			}
			if (line.startsWith("## ")) {
				addSection(out, heading, text);
				heading = line.substring(3).trim();
				text.setLength(0);
			}
			else {
				text.append(line).append('\n');
			}
		}
		addSection(out, heading, text);
		return out;
	}

	private static void addSection(List<Section> out, String heading, StringBuilder text) {
		String t = text.toString().strip();
		if (!t.isEmpty()) {
			out.add(new Section(heading, t));
		}
	}

	/** One piece if the section fits in maxTokens; otherwise greedy packing of boundary-respecting units. */
	List<String> split(String text, String language) {
		if (estimateTokens(text, language) <= props.maxTokens()) {
			return List.of(text);
		}
		List<String> units = units(text, language);
		List<String> pieces = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String unit : units) {
			String candidate = current.isEmpty() ? unit : current + "\n\n" + unit;
			if (!current.isEmpty() && estimateTokens(candidate, language) > props.targetTokens()) {
				pieces.add(current.toString());
				String overlap = tail(current.toString(), language);
				current.setLength(0);
				current.append(overlap.isEmpty() ? unit : overlap + "\n\n" + unit);
			}
			else {
				current.setLength(0);
				current.append(candidate);
			}
		}
		if (!current.isEmpty()) {
			String last = current.toString();
			if (!pieces.isEmpty() && estimateTokens(last, language) < props.minTokens()
					&& estimateTokens(pieces.getLast() + "\n\n" + last, language) <= props.maxTokens()) {
				pieces.set(pieces.size() - 1, pieces.getLast() + "\n\n" + last);
			}
			else {
				pieces.add(last);
			}
		}
		return pieces;
	}

	/** Paragraphs; paragraphs over maxTokens are broken into lines (lists), and lines over maxTokens into sentences. */
	private List<String> units(String text, String language) {
		List<String> units = new ArrayList<>();
		for (String paragraph : text.split("\\n\\s*\\n")) {
			if (estimateTokens(paragraph, language) <= props.maxTokens()) {
				units.add(paragraph.strip());
				continue;
			}
			for (String line : paragraph.split("\\n")) {
				if (estimateTokens(line, language) <= props.maxTokens()) {
					units.add(line.strip());
				}
				else {
					for (String sentence : SENTENCE_END.split(line)) {
						units.add(sentence.strip());
					}
				}
			}
		}
		units.removeIf(String::isBlank);
		return units;
	}

	/** Trailing whole sentences of a piece, up to overlapTokens (empty if even the last sentence is too long). */
	private String tail(String piece, String language) {
		String[] sentences = SENTENCE_END.split(piece.strip());
		StringBuilder tail = new StringBuilder();
		for (int i = sentences.length - 1; i >= 0; i--) {
			String candidate = tail.isEmpty() ? sentences[i] : sentences[i] + " " + tail;
			if (estimateTokens(candidate, language) > props.overlapTokens()) {
				break;
			}
			tail.setLength(0);
			tail.append(candidate);
		}
		return tail.toString();
	}

	/** Estimated tokens: words × a per-language factor. Good enough for sizing; not a real tokenizer. */
	public static int estimateTokens(String text, String language) {
		double factor = "ar".equals(language) ? TOKENS_PER_WORD_AR : TOKENS_PER_WORD_EN;
		return (int) Math.ceil(TextChecks.wordCount(text) * factor);
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
