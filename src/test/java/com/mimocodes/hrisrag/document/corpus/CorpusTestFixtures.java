package com.mimocodes.hrisrag.document.corpus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Small hand-made plans and facts for unit tests (independent of the real corpus-spec). */
final class CorpusTestFixtures {

	private CorpusTestFixtures() {
	}

	static final Map<String, Fact> FACTS = Map.of(
			"DAYS", new Fact("DAYS", "21", "Employees get 21 working days per year.", null, false, false, null),
			"NOTICE", new Fact("NOTICE", "14", "Request at least 14 days before.", null, false, false, null),
			"BANDS", new Fact("BANDS", "12,000 to 35,000", "Staff band is 12,000 to 35,000 EGP.", null, false, false, null),
			"TIME", new Fact("TIME", "09:00", "Work starts at 09:00.", null, false, false, null));

	static PlannedDocument plan(String lang, List<String> facts, String injection) {
		DocumentMetadata md = new DocumentMetadata("TEST-DOC-" + lang.toUpperCase(), "2.0",
				lang.equals("en") ? "Test Policy" : "سياسة اختبار", "LEAVE", "POLICY", lang, "ACTIVE",
				LocalDate.of(2026, 1, 1), null, null, "INTERNAL");
		return new PlannedDocument(md, "CORE", facts, List.of("Purpose", "Policy", "Exceptions"), false, 40, 120, null,
				null, injection);
	}

	static String englishBody(String policySentence) {
		return """
				# Test Policy

				## 1. Purpose
				This policy explains how the Company manages leave so that teams can plan their work with confidence and
				every employee understands the expectations that apply to them throughout the year.

				## 2. Policy
				%s Managers review every request carefully and confirm the outcome through the portal.

				## 3. Exceptions
				Exceptional situations are discussed with Human Resources, who document the decision and inform the employee
				and the manager about the agreed arrangement in writing.
				""".formatted(policySentence);
	}

	static String arabicBody(String policySentence) {
		return """
				# سياسة اختبار

				## 1. الغرض
				توضح هذه السياسة كيفية إدارة الإجازات في الشركة بحيث تتمكن الفرق من تخطيط العمل بثقة ويفهم كل موظف التوقعات
				المطبقة عليه طوال العام دون أي لبس أو غموض في الإجراءات المعتمدة.

				## 2. السياسة
				%s ويراجع المديرون كل طلب بعناية ويؤكدون النتيجة من خلال البوابة الإلكترونية.

				## 3. الاستثناءات
				تناقش الحالات الاستثنائية مع إدارة الموارد البشرية التي توثق القرار وتبلغ الموظف والمدير بالترتيب المتفق عليه
				كتابياً وبشكل واضح.
				""".formatted(policySentence);
	}
}
