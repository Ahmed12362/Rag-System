package com.mimocodes.hrisrag.rag.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.document.ingestion.SectionChunker;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;

/**
 * Turns retrieved chunks into numbered sources [S1..Sn], in rank order, within an estimated-token budget. Each source is
 * wrapped in a {@code <source>} element so the model can tell document data apart from instructions. Inner angle
 * brackets are neutralised so a document cannot close its own tag and inject text outside it.
 */
@Component
public class ContextBuilder {

	public record Context(String text, Map<String, RetrievedChunk> sources, int estimatedTokens) {
		public boolean isEmpty() {
			return sources.isEmpty();
		}
	}

	public Context build(List<RetrievedChunk> chunks, int maxTokens) {
		Map<String, RetrievedChunk> sources = new LinkedHashMap<>();
		List<String> parts = new ArrayList<>();
		int used = 0;
		for (RetrievedChunk c : chunks) {
			String id = "S" + (sources.size() + 1);
			String body = c.content().replace("<", "‹").replace(">", "›");
			String part = "<source id=\"" + id + "\" document=\"" + c.docKey() + "\" version=\"" + c.version()
					+ "\" section=\"" + c.sectionPath().replace("\"", "'") + "\">\n" + body + "\n</source>";
			int tokens = SectionChunker.estimateTokens(part, c.language());
			if (!sources.isEmpty() && used + tokens > maxTokens) {
				break; // budget reached; always keep at least the top-ranked source
			}
			sources.put(id, c);
			parts.add(part);
			used += tokens;
		}
		return new Context(String.join("\n\n", parts), sources, used);
	}
}
