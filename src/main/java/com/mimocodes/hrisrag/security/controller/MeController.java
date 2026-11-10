package com.mimocodes.hrisrag.security.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;
import com.mimocodes.hrisrag.security.AppUserPrincipal;

/** Who am I: the identity the assistant will use for the caller (never includes credentials). */
@RestController
@RequestMapping("/api/v1")
public class MeController {

	public record Me(String username, String employeeCode, String role, String department, String maxAccessLevel) {
	}

	@GetMapping("/me")
	public Me me(@AuthenticationPrincipal AppUserPrincipal principal) {
		UserContext u = principal.toUserContext();
		return new Me(principal.getUsername(), u.employeeCode(), u.role(), u.department(), u.maxAccessLevel());
	}
}
