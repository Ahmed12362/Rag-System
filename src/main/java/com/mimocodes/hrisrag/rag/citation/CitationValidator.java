package com.mimocodes.hrisrag.rag.citation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.rag.core.RagContract.Citation;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;

/**
 * Resolves [S#] markers in an answer against the sources actually sent. Citations of sources that were not sent are
 * removed from the answer and reported, so every citation returned to the user points to a real retrieved chunk.
 */
@Component
public class CitationValidator {

	private static final Pattern MARKER = Pattern.compile("\\[(S\\d+)\\]");

	public record Result(String answer, List<Citation> citations, List<String> invalidSourceIds) {
		public boolean uncited() {
			return citations.isEmpty();
		}
	}

	public Result validate(String answer, Map<String, RetrievedChunk> sentSources) {
		Set<String> valid = new LinkedHashSet<>();
		Set<String> invalid = new LinkedHashSet<>();
		Matcher m = MARKER.matcher(answer);
		while (m.find()) {
			(sentSources.containsKey(m.group(1)) ? valid : invalid).add(m.group(1));
		}
		String cleaned = answer;
		for (String bad : invalid) {
			cleaned = cleaned.replace("[" + bad + "]", "");
		}
		List<Citation> citations = new ArrayList<>();
		for (String id : valid) {
			RetrievedChunk c = sentSources.get(id);
			citations.add(new Citation(id, c.chunkId(), c.docKey(), c.version(), c.sectionPath()));
		}
		return new Result(cleaned.strip(), citations, List.copyOf(invalid));
	}
}
