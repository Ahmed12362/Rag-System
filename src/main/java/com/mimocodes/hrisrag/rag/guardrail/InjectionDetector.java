package com.mimocodes.hrisrag.rag.guardrail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Prompt-injection detection, a DEFENSE-IN-DEPTH layer. It is not the security boundary: access control happens in the
 * retrieval SQL and in {@link AccessPolicy} before any context is built, retrieved text is always wrapped as untrusted
 * data, and the system prompt tells the model to ignore instructions inside sources.
 * <p>
 * Patterns run on {@link TextNormalizer#normalize normalized} text and are deliberately narrow: they need an attack
 * construction ("ignore all previous instructions", "print your system prompt"), not a single word, so ordinary questions
 * such as "can I ignore a reminder email?" or "ما هي تعليمات استخدام القاعات؟" pass. Every rule has an id that is
 * recorded in the guardrail decisions.
 * <ul>
 * <li>{@link #scanInput}: the user's question (direct injection). Uses the {@code DIRECT} rules.</li>
 * <li>{@link #screenPassage}: a retrieved passage (indirect injection). Uses {@code DIRECT} plus rules for text that
 * addresses an AI ("SYSTEM NOTE TO AI ASSISTANTS:", "تعليمات للمساعد الذكي"), checked sentence by sentence so only the
 * injected sentences are removed and the legitimate policy text around them survives.</li>
 * </ul>
 */
@Component
public class InjectionDetector {

	public record Rule(String id, Pattern pattern) {
	}

	/** Result of screening one passage. {@code cleaned} has the flagged sentences removed. */
	public record Screening(String cleaned, List<String> rules, int removedSentences, int totalSentences) {
		public boolean flagged() {
			return !rules.isEmpty();
		}

		public boolean nothingLeft() {
			return removedSentences > 0 && removedSentences == totalSentences;
		}
	}

	private static final int F = Pattern.UNICODE_CHARACTER_CLASS;

	private static Rule rule(String id, String regex) {
		return new Rule(id, Pattern.compile(regex, F));
	}

	private static final String AR_IGNORE_VERB = "(تجاهل|انسي|انس|اهمل|تخطي|تخط|اتجاهل)";
	private static final String AR_INSTRUCTIONS = "(ال)?(تعليمات|اوامر|قواعد|توجيهات|كلام)";

	/** Attack constructions, for the question and for retrieved text. */
	static final List<Rule> DIRECT = List.of(
			rule("IGNORE_INSTRUCTIONS", "\\b(ignore|disregard|forget|override|bypass)\\s+(all\\s+(of\\s+)?)?(the\\s+)?"
					+ "(your|previous|prior|above|earlier|preceding|system|all)\\s+(\\w+\\s+)?"
					+ "(instructions?|rules|prompts?|guidelines|guidance|directives|commands)\\b"),
			rule("REVEAL_PROMPT", "\\b(system|developer)\\s+(prompt|message)s?\\b|\\bhidden\\s+(prompt|instructions)\\b"
					+ "|\\b(reveal|print|show|display|repeat|output|dump|tell me|give me|share|list)\\s+(me\\s+)?(all\\s+)?"
					+ "(of\\s+)?your\\s+(\\w+\\s+)?(instructions|prompt|configuration|config)\\b"
					+ "|\\bwhat\\s+(are|were)\\s+your\\s+(\\w+\\s+)?instructions\\b"),
			rule("ROLE_OVERRIDE", "\\b(you are now|from now on,? you are|pretend (to be|you are)|roleplay as)\\b"
					+ "|\\b(developer|god|admin|debug|sudo|jailbreak|dan)\\s+mode\\b|\\bjailbreak|\\bdo anything now\\b"
					+ "|\\bact as an? (unrestricted|unfiltered|jailbroken|uncensored)\\b"),
			rule("SECRET_REQUEST", "(?<![\\p{L}\\d])(api[\\s_-]*keys?|secret[\\s_-]*keys?|access[\\s_-]*tokens?|bearer\\s+tokens?"
					+ "|(database|db|admin|root|system)\\s+passwords?|connection\\s+strings?)\\b"),
			rule("DELIMITER_INJECTION", "</?\\s*(system|source|instructions?|assistant)\\b[^>]*>|\\[/?(system|inst)\\]"
					+ "|<<\\s*/?sys\\s*>>|<\\|im_(start|end)\\|>"),
			rule("AR_IGNORE_INSTRUCTIONS", AR_IGNORE_VERB + "\\s+(كل|جميع|كافه)\\s+" + AR_INSTRUCTIONS
					+ "|" + AR_IGNORE_VERB + "\\s+" + AR_INSTRUCTIONS
					+ "\\s+(ال)?(سابقه|اللي فاتت|اللي قبل كده|النظام|بتاعتك|الخاصه بك|المعطاه لك|الاصليه)"),
			rule("AR_REVEAL_PROMPT", "(اكشف|اعرض|اطبع|اظهر|وريني|ورني|قولي|قول لي|اعطني|اديني|هات|اكتب)\\s+(لي\\s+)?.{0,20}?"
					+ "(تعليمات النظام|تعليماتك|موجه النظام|رساله النظام|البرومبت|البرومت|الاوامر الخاصه بك)"
					+ "|(موجه|برومبت|برومت) النظام"),
			rule("AR_ROLE_OVERRIDE", "(انت الان|انت دلوقتي|من الان انت|من دلوقتي انت|تصرف ك|اتصرف ك|اتصرف زي)\\s*.{0,20}"
					+ "(مدير النظام|ادمن|مطور|مبرمج|بدون قيود|بلا قيود)|وضع المطور"),
			rule("AR_SECRET_REQUEST", "(مفتاح|مفاتيح)\\s+(ال\\s*)?(api|اي بي اي|واجهه البرمجه)"
					+ "|كلمه (السر|سر|المرور|مرور) (ل|الخاصه ب)?(قاعده البيانات|النظام|الادمن)"));

	/** Text that addresses an AI or asks for actions; only meaningful inside documents. */
	static final List<Rule> INDIRECT = List.of(
			rule("AI_ADDRESSED_NOTE", "\\b(note|notes|instruction|instructions|message|directive)\\s+(to|for)\\s+"
					+ "(the\\s+|all\\s+)?(ai|llm|assistant|chatbot|model|agent|bot)s?\\b"),
			rule("AI_ROLE_LABEL", "\\b(ai|llm)\\s+(assistant|agent|model|system)s?(\\s+(instruction|note|directive)s?)?\\s*:"
					+ "|^(assistant|system|ai|chatbot)\\s*:"),
			rule("TOOL_CALL_REQUEST", "\\b(call|invoke|execute)\\s+the\\s+(\\w+\\s+){0,3}tool\\b"),
			rule("AR_AI_ADDRESSED_NOTE", "(تعليمات|ملاحظه|رساله|توجيه)\\s+(لل|الي\\s+ال)(مساعد|نظام|ذكاء|بوت)"),
			rule("AR_TELL_USER", "(اخبر|قل|قول)\\s+(لل|ل)?(ال)?(مستخدم|مستخدمين)\\b"),
			rule("AR_REVEAL_DATA", "اكشف\\s+(عن\\s+)?(رواتب|مرتبات)"));

	/**
	 * Rules applied to retrieved passages. SECRET_REQUEST is excluded: a security policy that merely MENTIONS API keys
	 * or passwords is legitimate content, not an attack on the assistant.
	 */
	static final List<Rule> PASSAGE = Stream.concat(
			DIRECT.stream().filter(r -> !r.id().endsWith("SECRET_REQUEST")), INDIRECT.stream()).toList();

	private static final Pattern LINE = Pattern.compile("\\R");
	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?؟])\\s+");

	/** Rule ids matched by a user question (empty = no injection detected). */
	public List<String> scanInput(String question) {
		return match(TextNormalizer.normalize(question), DIRECT);
	}

	/** Screens a retrieved passage sentence by sentence and removes the sentences that match. */
	public Screening screenPassage(String content) {
		Set<String> rules = new LinkedHashSet<>();
		int removed = 0, total = 0;
		List<String> keptLines = new ArrayList<>();
		for (String line : LINE.split(content, -1)) {
			if (line.isBlank()) {
				keptLines.add(line);
				continue;
			}
			List<String> kept = new ArrayList<>();
			for (String sentence : SENTENCE_END.split(line.strip())) {
				total++;
				String n = TextNormalizer.normalize(sentence);
				List<String> hits = match(n, PASSAGE);
				if (hits.isEmpty()) {
					kept.add(sentence);
				}
				else {
					rules.addAll(hits);
					removed++;
				}
			}
			if (!kept.isEmpty()) {
				keptLines.add(String.join(" ", kept));
			}
		}
		String cleaned = String.join("\n", keptLines).replaceAll("\\n{3,}", "\n\n").strip();
		return new Screening(cleaned, List.copyOf(rules), removed, total);
	}

	private static List<String> match(String normalized, List<Rule> rules) {
		List<String> hits = new ArrayList<>();
		for (Rule r : rules) {
			if (r.pattern().matcher(normalized).find()) {
				hits.add(r.id());
			}
		}
		return hits;
	}
}
