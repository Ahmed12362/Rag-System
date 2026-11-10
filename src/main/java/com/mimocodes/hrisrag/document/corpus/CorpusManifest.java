package com.mimocodes.hrisrag.document.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * corpus/manifest.json: one entry per committed corpus file, keyed by relative path (sorted, so diffs are stable).
 * The checksum makes the frozen corpus verifiable; token usage records what generation cost.
 */
public final class CorpusManifest {

	public static final String FILE_NAME = "manifest.json";

	public record Entry(String docKey, String version, String language, String group, String sha256, String model,
			Integer promptTokens, Integer completionTokens, Integer thinkingTokens, long latencyMs, int attempts) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	private final Path root;
	private final Map<String, Entry> entries;

	private CorpusManifest(Path root, Map<String, Entry> entries) {
		this.root = root;
		this.entries = entries;
	}

	public static CorpusManifest load(Path corpusRoot) {
		Path file = corpusRoot.resolve(FILE_NAME);
		if (!Files.exists(file)) {
			return new CorpusManifest(corpusRoot, new TreeMap<>());
		}
		Map<String, Entry> read = JSON.readValue(file.toFile(), new TypeReference<TreeMap<String, Entry>>() { });
		return new CorpusManifest(corpusRoot, new TreeMap<>(read));
	}

	public void put(String relativePath, Entry entry) {
		entries.put(relativePath, entry);
	}

	public Map<String, Entry> entries() {
		return entries;
	}

	public void save() {
		try {
			Files.createDirectories(root);
			Files.writeString(root.resolve(FILE_NAME), JSON.writeValueAsString(entries) + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Paths whose file is missing or whose content no longer matches the recorded checksum. */
	public List<String> verifyChecksums() {
		List<String> problems = new ArrayList<>();
		entries.forEach((path, entry) -> {
			Path file = root.resolve(path);
			if (!Files.exists(file)) {
				problems.add(path + ": listed in manifest but missing");
			}
			else if (!sha256(file).equals(entry.sha256())) {
				problems.add(path + ": checksum mismatch (file changed after generation)");
			}
		});
		return problems;
	}

	public static String sha256(Path file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
