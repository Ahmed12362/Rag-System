package com.mimocodes.hrisrag.data.seed;

import java.util.List;

/**
 * Static bilingual vocabulary for synthetic data. Names are common Egyptian given and family names combined at random;
 * no record represents a real person. The order of every list is part of the deterministic output: append, don't reorder.
 */
final class ReferenceData {

	private ReferenceData() {
	}

	record Dept(String code, String nameEn, String nameAr, int weight, List<String> staffTitles) {}

	record Name(String en, String ar) {}

	record Bilingual(String titleEn, String bodyEn, String titleAr, String bodyAr) {}

	/** The first three are required by the anchor scenario and therefore exist in every profile. */
	static final List<Dept> DEPARTMENTS = List.of(
			new Dept("D-HR", "Human Resources", "الموارد البشرية", 4,
					List.of("HR Specialist", "Recruiter", "Payroll Specialist", "HR Coordinator")),
			new Dept("D-ENG", "Engineering", "الهندسة", 10,
					List.of("Backend Engineer", "Frontend Engineer", "QA Engineer", "DevOps Engineer", "Data Engineer")),
			new Dept("D-FIN", "Finance", "المالية", 4,
					List.of("Accountant", "Financial Analyst", "Accounts Payable Specialist")),
			new Dept("D-SAL", "Sales", "المبيعات", 8, List.of("Sales Executive", "Account Manager", "Sales Coordinator")),
			new Dept("D-MKT", "Marketing", "التسويق", 4, List.of("Marketing Specialist", "Content Writer", "Designer")),
			new Dept("D-OPS", "Operations", "العمليات", 6, List.of("Operations Specialist", "Logistics Coordinator")),
			new Dept("D-CS", "Customer Support", "دعم العملاء", 8, List.of("Support Agent", "Support Specialist")),
			new Dept("D-LEG", "Legal", "الشؤون القانونية", 2, List.of("Legal Counsel", "Contracts Specialist")),
			new Dept("D-IT", "IT", "تقنية المعلومات", 4, List.of("IT Support Engineer", "System Administrator")),
			new Dept("D-PRC", "Procurement", "المشتريات", 3, List.of("Procurement Officer", "Buyer")),
			new Dept("D-PRD", "Product", "إدارة المنتجات", 3, List.of("Product Manager", "Product Analyst")),
			new Dept("D-DAT", "Data & Analytics", "البيانات والتحليلات", 3, List.of("Data Analyst", "BI Developer")),
			new Dept("D-SEC", "Information Security", "أمن المعلومات", 2, List.of("Security Analyst", "Security Engineer")),
			new Dept("D-ADM", "Administration", "الشؤون الإدارية", 3, List.of("Office Administrator", "Receptionist")),
			new Dept("D-QA", "Quality Assurance", "ضمان الجودة", 3, List.of("Quality Specialist", "Auditor")),
			new Dept("D-TRN", "Training", "التدريب", 2, List.of("Trainer", "Learning Coordinator")),
			new Dept("D-FAC", "Facilities", "المرافق", 2, List.of("Facilities Coordinator", "Maintenance Technician")),
			new Dept("D-RND", "Research", "البحث والتطوير", 3, List.of("Research Engineer", "Research Analyst")),
			new Dept("D-PRJ", "Project Management", "إدارة المشاريع", 3, List.of("Project Manager", "Project Coordinator")),
			new Dept("D-CMP", "Compliance", "الامتثال", 2, List.of("Compliance Officer", "Risk Analyst")),
			new Dept("D-PR", "Public Relations", "العلاقات العامة", 2, List.of("PR Specialist", "Communications Officer")),
			new Dept("D-BD", "Business Development", "تطوير الأعمال", 3, List.of("Business Developer", "Partnerships Manager")),
			new Dept("D-SCM", "Supply Chain", "سلسلة الإمداد", 3, List.of("Supply Chain Analyst", "Planner")),
			new Dept("D-WH", "Warehouse", "المستودعات", 4, List.of("Warehouse Keeper", "Inventory Clerk")),
			new Dept("D-TRP", "Transportation", "النقل", 3, List.of("Fleet Coordinator", "Driver")),
			new Dept("D-AUD", "Internal Audit", "التدقيق الداخلي", 2, List.of("Internal Auditor")),
			new Dept("D-STR", "Strategy", "الاستراتيجية", 2, List.of("Strategy Analyst")),
			new Dept("D-UX", "User Experience", "تجربة المستخدم", 2, List.of("UX Designer", "UX Researcher")),
			new Dept("D-MOB", "Mobile", "تطبيقات الجوال", 3, List.of("Android Engineer", "iOS Engineer")),
			new Dept("D-INF", "Infrastructure", "البنية التحتية", 3, List.of("Cloud Engineer", "Network Engineer")));

	static final List<Name> MALE_FIRST = List.of(
			new Name("Ahmed", "أحمد"), new Name("Mohamed", "محمد"), new Name("Mahmoud", "محمود"), new Name("Mostafa", "مصطفى"),
			new Name("Omar", "عمر"), new Name("Youssef", "يوسف"), new Name("Karim", "كريم"), new Name("Hassan", "حسن"),
			new Name("Hussein", "حسين"), new Name("Ali", "علي"), new Name("Tarek", "طارق"), new Name("Amr", "عمرو"),
			new Name("Sherif", "شريف"), new Name("Hany", "هاني"), new Name("Waleed", "وليد"), new Name("Ibrahim", "إبراهيم"),
			new Name("Khaled", "خالد"), new Name("Mina", "مينا"), new Name("Bishoy", "بيشوي"), new Name("Ramy", "رامي"),
			new Name("Ehab", "إيهاب"), new Name("Adel", "عادل"), new Name("Sameh", "سامح"), new Name("Hesham", "هشام"),
			new Name("Ayman", "أيمن"), new Name("Nader", "نادر"), new Name("Ziad", "زياد"), new Name("Marwan", "مروان"),
			new Name("Seif", "سيف"), new Name("Yassin", "ياسين"));

	static final List<Name> FEMALE_FIRST = List.of(
			new Name("Mona", "منى"), new Name("Laila", "ليلى"), new Name("Nour", "نور"), new Name("Sara", "سارة"),
			new Name("Mariam", "مريم"), new Name("Fatma", "فاطمة"), new Name("Aya", "آية"), new Name("Dina", "دينا"),
			new Name("Rania", "رانيا"), new Name("Heba", "هبة"), new Name("Yasmin", "ياسمين"), new Name("Salma", "سلمى"),
			new Name("Hana", "هنا"), new Name("Nada", "ندى"), new Name("Reem", "ريم"), new Name("Amira", "أميرة"),
			new Name("Marina", "مارينا"), new Name("Mirna", "ميرنا"), new Name("Eman", "إيمان"), new Name("Shaimaa", "شيماء"),
			new Name("Asmaa", "أسماء"), new Name("Doaa", "دعاء"), new Name("Esraa", "إسراء"), new Name("Ghada", "غادة"),
			new Name("Noha", "نهى"), new Name("Radwa", "رضوى"), new Name("Habiba", "حبيبة"), new Name("Malak", "ملك"),
			new Name("Jana", "جنى"), new Name("Farida", "فريدة"));

	static final List<Name> FAMILY = List.of(
			new Name("Hassan", "حسن"), new Name("Mansour", "منصور"), new Name("Samir", "سمير"), new Name("Adel", "عادل"),
			new Name("Nabil", "نبيل"), new Name("Farouk", "فاروق"), new Name("Abdelrahman", "عبد الرحمن"),
			new Name("El-Sayed", "السيد"), new Name("Mostafa", "مصطفى"), new Name("Ibrahim", "إبراهيم"),
			new Name("Saleh", "صالح"), new Name("Kamal", "كمال"), new Name("Fawzy", "فوزي"), new Name("Hamdy", "حمدي"),
			new Name("Gaber", "جابر"), new Name("Shawky", "شوقي"), new Name("Ragab", "رجب"), new Name("Zaki", "زكي"),
			new Name("Ezzat", "عزت"), new Name("Lotfy", "لطفي"), new Name("Ashraf", "أشرف"), new Name("Fathy", "فتحي"),
			new Name("Girgis", "جرجس"), new Name("Hanna", "حنا"), new Name("Wahba", "وهبة"), new Name("Tawfik", "توفيق"),
			new Name("Rashad", "رشاد"), new Name("Sobhy", "صبحي"), new Name("Morsy", "مرسي"), new Name("Shalaby", "شلبي"),
			new Name("Abdallah", "عبد الله"), new Name("Youssef", "يوسف"), new Name("Mahmoud", "محمود"),
			new Name("Osman", "عثمان"), new Name("Hegazy", "حجازي"), new Name("Badawy", "بدوي"), new Name("Salem", "سالم"),
			new Name("Radwan", "رضوان"), new Name("Khalil", "خليل"), new Name("Naguib", "نجيب"));

	static final List<String> MEETING_TITLES = List.of(
			"Weekly team sync", "Sprint planning", "Sprint review", "Retrospective", "One-on-one",
			"Quarterly business review", "Budget review", "Hiring panel", "Onboarding session", "Project kickoff",
			"Client call preparation", "Process improvement workshop", "Risk review", "Training session",
			"Performance calibration", "Department all-hands", "Vendor evaluation", "Incident post-mortem");

	static final List<String> LOCATIONS = List.of(
			"Room Nile", "Room Giza", "Room Luxor", "Room Aswan", "Room Alexandria", "Main hall", "Online (Teams)",
			"Online (Zoom)");

	static final List<String> REWARD_REASONS = List.of(
			"Delivered the quarterly release ahead of schedule",
			"Outstanding customer feedback",
			"Mentored new team members",
			"Led a successful cost-saving initiative",
			"Resolved a critical production incident",
			"Exceeded quarterly targets",
			"Perfect attendance for the quarter",
			"Completed a professional certification",
			"Improved a key internal process",
			"Recognised by peers for teamwork");

	static final List<String> DISCIPLINARY_REASONS = List.of(
			"Repeated lateness beyond the grace period",
			"Unexcused absence",
			"Failure to follow the leave request procedure",
			"Breach of the remote work policy",
			"Misuse of company equipment",
			"Missing mandatory training deadlines");

	static final List<String> LEAVE_REASONS = List.of(
			"Family event", "Travel", "Personal matters", "Rest", "Medical appointment", "Wedding", "Moving house",
			"Childcare", "Exam preparation");

	/** Announcement templates; "{dept}" is replaced with the department name in the matching language. */
	static final List<Bilingual> ANNOUNCEMENTS = List.of(
			new Bilingual("Office closure for public holiday", "The office will be closed for the upcoming public holiday. Urgent support follows the on-call rota.",
					"إغلاق المكتب بمناسبة العطلة الرسمية", "سيتم إغلاق المكتب بمناسبة العطلة الرسمية القادمة، ويتم التعامل مع الحالات العاجلة وفق جدول المناوبة."),
			new Bilingual("Annual leave planning reminder", "Please submit your annual leave plans for the next quarter through the HR system.",
					"تذكير بتخطيط الإجازة السنوية", "يرجى تقديم خطط الإجازة السنوية للربع القادم من خلال نظام الموارد البشرية."),
			new Bilingual("New attendance system rollout", "Check-in will move to the new badge readers starting next month.",
					"تشغيل نظام الحضور الجديد", "سيتم تسجيل الحضور من خلال أجهزة البطاقات الجديدة بداية من الشهر القادم."),
			new Bilingual("Health insurance renewal", "The medical insurance renewal window is open. Update your dependants before the deadline.",
					"تجديد التأمين الصحي", "باب تجديد التأمين الطبي مفتوح الآن، يرجى تحديث بيانات المعالين قبل الموعد النهائي."),
			new Bilingual("{dept} team offsite", "The {dept} team offsite will take place next month. Details will follow by email.",
					"الرحلة الخارجية لفريق {dept}", "ستقام الرحلة الخارجية لفريق {dept} الشهر القادم، وسيتم إرسال التفاصيل عبر البريد الإلكتروني."),
			new Bilingual("{dept} all-hands meeting", "Join the {dept} all-hands to review quarterly results and priorities.",
					"الاجتماع العام لإدارة {dept}", "انضموا إلى الاجتماع العام لإدارة {dept} لمراجعة نتائج الربع والأولويات."),
			new Bilingual("Mandatory information security training", "All employees must complete the information security course this month.",
					"تدريب إلزامي على أمن المعلومات", "يجب على جميع الموظفين إكمال دورة أمن المعلومات خلال هذا الشهر."),
			new Bilingual("Ramadan working hours", "Reduced working hours apply during Ramadan as described in the working hours policy.",
					"مواعيد العمل في رمضان", "يتم تطبيق ساعات العمل المخفضة خلال شهر رمضان وفقاً لسياسة ساعات العمل."),
			new Bilingual("Employee recognition programme", "Nominations for this quarter's recognition awards are now open.",
					"برنامج تقدير الموظفين", "باب الترشيحات لجوائز التقدير لهذا الربع مفتوح الآن."),
			new Bilingual("Remote work guidelines update", "The remote work guidelines have been updated. Please review the new version.",
					"تحديث إرشادات العمل عن بعد", "تم تحديث إرشادات العمل عن بعد، يرجى مراجعة النسخة الجديدة."),
			new Bilingual("{dept} new joiners", "Please welcome the new members joining {dept} this month.",
					"موظفون جدد في {dept}", "نرحب بالزملاء الجدد المنضمين إلى {dept} هذا الشهر."),
			new Bilingual("Parking area maintenance", "The parking area will be partially closed for maintenance this weekend.",
					"صيانة منطقة الانتظار", "سيتم إغلاق جزء من منطقة انتظار السيارات للصيانة خلال عطلة نهاية الأسبوع."));
}
