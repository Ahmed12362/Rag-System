package com.mimocodes.hrisrag.data.employee.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RESTRICTED data (HR_ADMIN only). Exists so salary-disclosure security tests have a real target.
 * Must never be embedded, indexed for retrieval, or returned to the LLM for unauthorized users.
 */
@Entity
@Table(name = "employee_compensation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeCompensation {

	@Id
	@Column(name = "employee_id")
	private Long employeeId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id")
	private Employee employee;

	@Column(name = "monthly_salary", nullable = false, precision = 12, scale = 2)
	private BigDecimal monthlySalary;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	public EmployeeCompensation(Employee employee, BigDecimal monthlySalary, String currency, LocalDate effectiveFrom) {
		this.employee = employee;
		this.monthlySalary = monthlySalary;
		this.currency = currency;
		this.effectiveFrom = effectiveFrom;
	}
}
