package com.mimocodes.hrisrag.document.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.document.corpus.CorpusManifest;
import com.mimocodes.hrisrag.document.corpus.DocumentMetadata;

/** Loads corpus Markdown files (front matter + body). Unreadable files are reported, never silently skipped. */
@Component
public class CorpusLoader {

	public record LoadedDocument(String relativePath, DocumentMetadata metadata, String body, String contentHash) {
		public String id() {
			return metadata.docKey() + "@" + metadata.version();
		}
	}

	public record LoadFailure(String relativePath, String stage, String message) {
	}

	public record Result(List<LoadedDocument> documents, List<LoadFailure> failures) {
	}

	public Result load(Path corpusRoot) {
		List<LoadedDocument> docs = new ArrayList<>();
		List<LoadFailure> failures = new ArrayList<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(corpusRoot)) {
			files = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot list corpus " + corpusRoot, e);
		}
		for (Path file : files) {
			String rel = corpusRoot.relativize(file).toString().replace('\\', '/');
			String content;
			try {
				content = Files.readString(file, StandardCharsets.UTF_8);
			}
			catch (IOException | UncheckedIOException e) {
				failures.add(new LoadFailure(rel, "LOAD", e.getMessage()));
				continue;
			}
			try {
				DocumentMetadata.Parsed parsed = DocumentMetadata.parse(content);
				DocumentMetadata md = parsed.metadata();
				if (md.docKey() == null || md.version() == null || md.title() == null || md.language() == null
						|| md.status() == null || md.accessLevel() == null || md.effectiveDate() == null) {
					throw new IllegalArgumentException("front matter is missing required fields");
				}
				docs.add(new LoadedDocument(rel, md, parsed.body(), CorpusManifest.sha256(file)));
			}
			catch (RuntimeException e) {
				failures.add(new LoadFailure(rel, "PARSE", String.valueOf(e.getMessage())));
			}
		}
		return new Result(docs, failures);
	}
}
