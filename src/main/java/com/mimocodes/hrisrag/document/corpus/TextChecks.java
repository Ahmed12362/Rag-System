package com.mimocodes.hrisrag.document.corpus;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Language-neutral text measurements used by the corpus validator. */
public final class TextChecks {

	private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
	private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d)[,\u066C](?=\\d{3}(?!\\d))");
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");
	private static final Pattern ARABIC_LETTER = Pattern.compile("[\\u0600-\\u06FF]");
	private static final Pattern LETTER = Pattern.compile("\\p{L}");

	private TextChecks() {
	}

	/** Arabic-Indic and Persian digits → ASCII; removes thousands separators (12,000 / ١٢٬٠٠٠ → 12000). */
	public static String normalizeDigits(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (char c : text.toCharArray()) {
			if (c >= '\u0660' && c <= '\u0669') {
				sb.append((char) ('0' + (c - '\u0660')));
			}
			else if (c >= '\u06F0' && c <= '\u06F9') {
				sb.append((char) ('0' + (c - '\u06F0')));
			}
			else {
				sb.append(c);
			}
		}
		return THOUSANDS.matcher(sb).replaceAll("");
	}

	/** All numbers in the text, normalized (digits, no thousands separators, no leading zeros: "09" → "9"). */
	public static Set<String> numbers(String text) {
		Set<String> out = new LinkedHashSet<>();
		if (text == null) {
			return out;
		}
		Matcher m = NUMBER.matcher(normalizeDigits(text));
		while (m.find()) {
			out.add(canonical(m.group()));
		}
		return out;
	}

	static String canonical(String number) {
		String n = number.contains(".") ? number : number.replaceFirst("^0+(?=\\d)", "");
		return n;
	}

	public static int wordCount(String text) {
		int n = 0;
		Matcher m = WORD.matcher(text);
		while (m.find()) {
			n++;
		}
		return n;
	}

	/** Share of letters that are Arabic script (0..1). */
	public static double arabicRatio(String text) {
		long letters = LETTER.matcher(text).results().count();
		if (letters == 0) {
			return 0;
		}
		return (double) ARABIC_LETTER.matcher(text).results().count() / letters;
	}

	/** Collapses whitespace so verbatim checks are not defeated by line wrapping. */
	public static String squash(String text) {
		return text.replaceAll("\\s+", " ").trim();
	}
}
