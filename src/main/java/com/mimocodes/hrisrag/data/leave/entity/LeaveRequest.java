package com.mimocodes.hrisrag.data.leave.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mimocodes.hrisrag.data.employee.entity.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Stable business key, e.g. LR-1005. */
	@Column(name = "request_code", nullable = false, unique = true, length = 20)
	private String requestCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Employee employee;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "leave_type_id", nullable = false)
	private LeaveType leaveType;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(nullable = false, precision = 5, scale = 1)
	private BigDecimal days;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private LeaveRequestStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approver_id")
	private Employee approver;

	@Column(length = 500)
	private String reason;

	@Column(name = "submitted_at", nullable = false)
	private Instant submittedAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	public LeaveRequest(String requestCode, Employee employee, LeaveType leaveType, LocalDate startDate,
			LocalDate endDate, BigDecimal days, LeaveRequestStatus status, Employee approver, String reason,
			Instant submittedAt, Instant decidedAt) {
		this.requestCode = requestCode;
		this.employee = employee;
		this.leaveType = leaveType;
		this.startDate = startDate;
		this.endDate = endDate;
		this.days = days;
		this.status = status;
		this.approver = approver;
		this.reason = reason;
		this.submittedAt = submittedAt;
		this.decidedAt = decidedAt;
	}
}
