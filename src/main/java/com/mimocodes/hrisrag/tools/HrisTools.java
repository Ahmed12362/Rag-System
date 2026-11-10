package com.mimocodes.hrisrag.tools;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.data.query.HrisQueryService;
import com.mimocodes.hrisrag.data.query.HrisQueryService.EmployeeRef;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The allowlisted personal-data tools (search_policies is handled by the agent, because it needs retrieval). Every call
 * goes through the same steps, in order:
 * <ol>
 * <li>unknown tool → INVALID;</li>
 * <li>arguments parsed and validated against a strict allowlist (unknown keys, bad formats and out-of-range values are
 * rejected; nothing is passed to SQL as text);</li>
 * <li>the target employee resolved (omitted = the caller) and AUTHORIZED against the authenticated caller;</li>
 * <li>only then the read-only query, through {@link HrisQueryService}.</li>
 * </ol>
 * Responses are small JSON documents with a {@code status}; a denial carries no data.
 */
@Component
public class HrisTools {

	public static final String LEAVE_BALANCE = "get_leave_balance";
	public static final String LEAVE_REQUEST_STATUS = "get_leave_request_status";
	public static final String UPCOMING_MEETINGS = "get_upcoming_meetings";
	public static final String ATTENDANCE_SUMMARY = "get_attendance_summary";

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final Pattern EMPLOYEE_CODE = Pattern.compile("^[A-Z]{1,3}-\\d{4}$");
	static final Pattern REQUEST_CODE = Pattern.compile("^LR-\\d{4,6}$");
	static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
	static final Set<String> LEAVE_TYPES = Set.of("ANNUAL", "SICK", "CASUAL", "UNPAID");

	private static final String EMPLOYEE_PROPERTY = """
			"employee": {"type": "string", "description": "Employee code such as E-1001. Omit it to mean the current user."}""";

	/** Result of handling one call: the JSON returned to the model, plus what the trace records. */
	public record Execution(String outcome, String reason, Map<String, Object> arguments, String responseJson) {
	}

	private final HrisQueryService hris;
	private final ToolAuthorizer authorizer;
	private final ToolsProperties props;

	public HrisTools(HrisQueryService hris, ToolAuthorizer authorizer, ToolsProperties props) {
		this.hris = hris;
		this.authorizer = authorizer;
		this.props = props;
	}

	public List<ToolDefinition> definitions() {
		return List.of(
				def(LEAVE_BALANCE, "Leave balance (entitled, used, pending and remaining days) for one leave type and year.",
						"{" + EMPLOYEE_PROPERTY + ", \"leave_type\": {\"type\": \"string\", \"enum\": "
								+ "[\"ANNUAL\", \"SICK\", \"CASUAL\", \"UNPAID\"], \"description\": \"Default ANNUAL.\"}, "
								+ "\"year\": {\"type\": \"integer\", \"description\": \"Default: the current year.\"}}",
						"[]"),
				def(LEAVE_REQUEST_STATUS, "Status, dates and type of one leave request, by its code (e.g. LR-1005).",
						"{\"request_code\": {\"type\": \"string\", \"description\": \"Leave request code, e.g. LR-1005.\"}}",
						"[\"request_code\"]"),
				def(UPCOMING_MEETINGS, "Upcoming meetings (soonest first), excluding cancelled meetings and declined "
						+ "invitations.", "{" + EMPLOYEE_PROPERTY + "}", "[]"),
				def(ATTENDANCE_SUMMARY, "Attendance summary for one month: late days, minutes late, absences, remote "
						+ "and leave days.", "{" + EMPLOYEE_PROPERTY + ", \"month\": {\"type\": \"string\", "
								+ "\"description\": \"Month as YYYY-MM. Default: the current month.\"}}", "[]"));
	}

	private static ToolDefinition def(String name, String description, String properties, String required) {
		return ToolDefinition.builder().name(name).description(description)
				.inputSchema("{\"type\": \"object\", \"properties\": " + properties + ", \"required\": " + required
						+ ", \"additionalProperties\": false}")
				.build();
	}

	public boolean handles(String name) {
		return Set.of(LEAVE_BALANCE, LEAVE_REQUEST_STATUS, UPCOMING_MEETINGS, ATTENDANCE_SUMMARY).contains(name);
	}

	public Execution execute(String name, String argumentsJson, UserContext caller) {
		if (!handles(name)) {
			return invalid(Map.of(), "UNKNOWN_TOOL " + name);
		}
		Map<String, Object> args;
		try {
			args = argumentsJson == null || argumentsJson.isBlank() ? Map.of()
					: JSON.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
					});
		}
		catch (RuntimeException e) {
			return invalid(Map.of(), "arguments are not a JSON object");
		}
		try {
			return switch (name) {
				case LEAVE_BALANCE -> leaveBalance(args, caller);
				case LEAVE_REQUEST_STATUS -> leaveRequest(args, caller);
				case UPCOMING_MEETINGS -> meetings(args, caller);
				default -> attendance(args, caller);
			};
		}
		catch (InvalidArgument e) {
			// Trace only the argument NAMES of a rejected call: the values came from the model and may be arbitrary.
			return invalid(Map.of("keys", List.copyOf(args.keySet())), e.getMessage());
		}
	}

	// ---------------------------------------------------------------- tools

	private Execution leaveBalance(Map<String, Object> raw, UserContext caller) {
		allowOnly(raw, "employee", "leave_type", "year");
		String employee = employee(raw, caller);
		String type = string(raw, "leave_type", "ANNUAL").toUpperCase();
		if (!LEAVE_TYPES.contains(type)) {
			throw new InvalidArgument("leave_type must be one of " + LEAVE_TYPES);
		}
		int year = integer(raw, "year", props.referenceDate().getYear(), 2000, 2100);
		Map<String, Object> args = ordered("employee", employee, "leave_type", type, "year", year);
		var auth = authorize(caller, employee);
		if (!auth.allowed()) {
			return denied(args, auth.reason());
		}
		return hris.balance(employee, type, year)
				.map(b -> ok(args, auth.reason(), Map.of("balance", b)))
				.orElseGet(() -> notFound(args, auth.reason(), "no " + type + " balance for " + year));
	}

	private Execution leaveRequest(Map<String, Object> raw, UserContext caller) {
		allowOnly(raw, "request_code");
		String code = string(raw, "request_code", null);
		if (code == null || !REQUEST_CODE.matcher(code.toUpperCase()).matches()) {
			throw new InvalidArgument("request_code must look like LR-1005");
		}
		code = code.toUpperCase();
		Map<String, Object> args = ordered("request_code", code);
		var request = hris.request(code);
		// Authorize against the request's OWNER. An unknown code and someone else's request look the same to the caller.
		String owner = request.map(HrisQueryService.Request::employeeCode).orElse(null);
		var auth = owner == null ? new ToolAuthorizer.Decision(false, "NOT_OWN_DATA") : authorize(caller, owner);
		if (!auth.allowed()) {
			return denied(args, auth.reason());
		}
		var r = request.get();
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("request_code", r.requestCode());
		v.put("employee", r.employeeCode());
		v.put("leave_type", r.leaveType());
		v.put("start_date", r.startDate().toString());
		v.put("end_date", r.endDate().toString());
		v.put("days", r.days());
		v.put("status", r.status());
		v.put("submitted_at", local(r.submittedAt()));
		v.put("decided_at", local(r.decidedAt()));
		return ok(args, auth.reason(), Map.of("time_zone", props.zone().getId(), "request", v));
	}

	private Execution meetings(Map<String, Object> raw, UserContext caller) {
		allowOnly(raw, "employee");
		String employee = employee(raw, caller);
		Map<String, Object> args = ordered("employee", employee);
		var auth = authorize(caller, employee);
		if (!auth.allowed()) {
			return denied(args, auth.reason());
		}
		var now = props.referenceDate().atStartOfDay(props.zone()).toInstant();
		// Times in the COMPANY time zone (found by the real run: UTC instants made the model answer "08:00 UTC").
		var list = hris.upcomingMeetings(employee, now, props.maxMeetings()).stream().map(m -> {
			Map<String, Object> v = new LinkedHashMap<>();
			v.put("meeting_code", m.meetingCode());
			v.put("title", m.title());
			v.put("starts_at", local(m.startsAt()));
			v.put("ends_at", local(m.endsAt()));
			v.put("location", m.location());
			v.put("status", m.status());
			return v;
		}).toList();
		return ok(args, auth.reason(), Map.of("as_of", props.referenceDate().toString(), "time_zone",
				props.zone().getId(), "meetings", list));
	}

	private Execution attendance(Map<String, Object> raw, UserContext caller) {
		allowOnly(raw, "employee", "month");
		String employee = employee(raw, caller);
		String month = string(raw, "month", YearMonth.from(props.referenceDate()).toString());
		if (!MONTH.matcher(month).matches()) {
			throw new InvalidArgument("month must be YYYY-MM");
		}
		YearMonth ym = YearMonth.parse(month);
		if (ym.isAfter(YearMonth.from(props.referenceDate()))) {
			throw new InvalidArgument("month " + month + " is in the future");
		}
		Map<String, Object> args = ordered("employee", employee, "month", month);
		var auth = authorize(caller, employee);
		if (!auth.allowed()) {
			return denied(args, auth.reason());
		}
		return ok(args, auth.reason(), Map.of("summary", hris.attendance(employee, ym)));
	}

	// ---------------------------------------------------------------- validation and authorization

	private ToolAuthorizer.Decision authorize(UserContext caller, String targetEmployee) {
		Optional<EmployeeRef> target = caller.employeeCode().equals(targetEmployee) ? Optional.empty()
				: hris.employee(targetEmployee);
		return authorizer.decide(caller, targetEmployee, target);
	}

	/** Omitted employee = the caller (the identity comes from the login, never from the model). */
	private static String employee(Map<String, Object> raw, UserContext caller) {
		String e = string(raw, "employee", caller.employeeCode()).toUpperCase();
		if (!EMPLOYEE_CODE.matcher(e).matches()) {
			throw new InvalidArgument("employee must be an employee code such as E-1001");
		}
		return e;
	}

	private static void allowOnly(Map<String, Object> raw, String... keys) {
		Set<String> allowed = Set.of(keys);
		for (String k : raw.keySet()) {
			if (!allowed.contains(k)) {
				throw new InvalidArgument("unknown argument '" + k + "'");
			}
		}
	}

	private static String string(Map<String, Object> raw, String key, String fallback) {
		Object v = raw.get(key);
		if (v == null || v.toString().isBlank()) {
			return fallback;
		}
		if (!(v instanceof String s) || s.length() > 40) {
			throw new InvalidArgument(key + " must be a short string");
		}
		return s.strip();
	}

	private static int integer(Map<String, Object> raw, String key, int fallback, int min, int max) {
		Object v = raw.get(key);
		if (v == null) {
			return fallback;
		}
		int n;
		if (v instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())) {
			n = num.intValue();
		}
		else if (v instanceof String s && s.matches("\\d{1,4}")) {
			n = Integer.parseInt(s);
		}
		else {
			throw new InvalidArgument(key + " must be an integer");
		}
		if (n < min || n > max) {
			throw new InvalidArgument(key + " must be between " + min + " and " + max);
		}
		return n;
	}

	/** "2026-11-03T10:00" in the company zone, or null. */
	private String local(java.time.Instant instant) {
		return instant == null ? null
				: instant.atZone(props.zone()).toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
						.toString();
	}

	private static Map<String, Object> ordered(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	// ---------------------------------------------------------------- responses

	private static Execution ok(Map<String, Object> args, String reason, Map<String, Object> data) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "OK");
		body.putAll(data);
		return new Execution("OK", reason, args, JSON.writeValueAsString(body));
	}

	private static Execution notFound(Map<String, Object> args, String reason, String message) {
		return new Execution("NOT_FOUND", reason, args,
				JSON.writeValueAsString(Map.of("status", "NOT_FOUND", "message", message)));
	}

	private static Execution denied(Map<String, Object> args, String reason) {
		return new Execution("DENIED", reason, args, JSON.writeValueAsString(Map.of("status", "NOT_AUTHORIZED",
				"message", "The current user is not allowed to view this record. Tell the user you cannot share it.")));
	}

	private static Execution invalid(Map<String, Object> args, String message) {
		return new Execution("INVALID", message, args,
				JSON.writeValueAsString(Map.of("status", "INVALID_ARGUMENTS", "message", message)));
	}

	static final class InvalidArgument extends RuntimeException {
		InvalidArgument(String message) {
			super(message);
		}
	}
}
