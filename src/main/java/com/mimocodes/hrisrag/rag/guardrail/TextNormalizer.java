package com.mimocodes.hrisrag.rag.guardrail;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical form for pattern matching only (never shown to users or sent to the model). Removes the usual tricks that
 * defeat naive matching: compatibility characters (full-width letters), zero-width and bidi control characters, Arabic
 * diacritics and tatweel, letter variants (أ/إ/آ → ا, ى → ي, ة → ه), case and repeated whitespace.
 */
public final class TextNormalizer {

	private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]");
	private static final Pattern ARABIC_MARKS = Pattern.compile("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private TextNormalizer() {
	}

	public static String normalize(String text) {
		if (text == null) {
			return "";
		}
		String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
		s = INVISIBLE.matcher(s).replaceAll("");
		s = ARABIC_MARKS.matcher(s).replaceAll("");
		s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه');
		s = s.toLowerCase(Locale.ROOT);
		return SPACES.matcher(s).replaceAll(" ").strip();
	}
}
