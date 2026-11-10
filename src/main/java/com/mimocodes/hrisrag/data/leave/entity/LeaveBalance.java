package com.mimocodes.hrisrag.data.leave.entity;

import java.math.BigDecimal;

import com.mimocodes.hrisrag.data.employee.entity.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "leave_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveBalance {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Employee employee;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "leave_type_id", nullable = false)
	private LeaveType leaveType;

	@Column(nullable = false)
	private short year;

	@Column(name = "entitled_days", nullable = false, precision = 5, scale = 1)
	private BigDecimal entitledDays;

	@Column(name = "used_days", nullable = false, precision = 5, scale = 1)
	private BigDecimal usedDays;

	@Column(name = "pending_days", nullable = false, precision = 5, scale = 1)
	private BigDecimal pendingDays;

	public LeaveBalance(Employee employee, LeaveType leaveType, short year, BigDecimal entitledDays,
			BigDecimal usedDays, BigDecimal pendingDays) {
		this.employee = employee;
		this.leaveType = leaveType;
		this.year = year;
		this.entitledDays = entitledDays;
		this.usedDays = usedDays;
		this.pendingDays = pendingDays;
	}

	/** Derived, never stored: entitled − used − pending (single source of truth). */
	public BigDecimal remainingDays() {
		return entitledDays.subtract(usedDays).subtract(pendingDays);
	}
}
