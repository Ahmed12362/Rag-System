package com.mimocodes.hrisrag.tools;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.data.query.HrisQueryService.EmployeeRef;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * Who may read whose personal HR records. The caller is ALWAYS the authenticated user; an employee code in the
 * question or in the model's tool arguments is only the requested target. Decided in code, never by the LLM.
 * <ul>
 * <li>Anyone: their own records (SELF).</li>
 * <li>MANAGER: also their direct reports (DIRECT_REPORT).</li>
 * <li>HR_ADMIN: everyone (HR_ADMIN).</li>
 * <li>Otherwise NOT_OWN_DATA. An unknown employee gets the same denial, so callers cannot probe which codes exist.</li>
 * </ul>
 */
@Component
public class ToolAuthorizer {

	public record Decision(boolean allowed, String reason) {
	}

	public Decision decide(UserContext caller, String targetEmployeeCode, Optional<EmployeeRef> target) {
		if (caller.employeeCode().equals(targetEmployeeCode)) {
			return new Decision(true, "SELF");
		}
		if (target.isEmpty()) {
			return new Decision(false, "NOT_OWN_DATA");
		}
		if ("HR_ADMIN".equals(caller.role())) {
			return new Decision(true, "HR_ADMIN");
		}
		if ("MANAGER".equals(caller.role()) && caller.employeeCode().equals(target.get().managerCode())) {
			return new Decision(true, "DIRECT_REPORT");
		}
		return new Decision(false, "NOT_OWN_DATA");
	}
}
