package com.mimocodes.hrisrag.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;

/** Prominent start/finish banners and report files for command-line runs (corpus generation, ingestion, ...). */
public final class RunReports {

	private RunReports() {
	}

	public static void banner(Logger log, String title, String details) {
		String line = "=".repeat(78);
		log.info("\n{}\n  {}\n  {}\n{}", line, title, details.replace("\n", "\n  "), line);
	}

	/** Writes {dir}/{kind}-{timestamp}.txt and {dir}/latest-{kind}.txt; returns the timestamped file. */
	public static Path write(String dir, String kind, String content) {
		try {
			Path d = Path.of(dir);
			Files.createDirectories(d);
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path file = d.resolve(kind + "-" + stamp + ".txt");
			Files.writeString(file, content, StandardCharsets.UTF_8);
			Files.writeString(d.resolve("latest-" + kind + ".txt"), content, StandardCharsets.UTF_8);
			return file;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
