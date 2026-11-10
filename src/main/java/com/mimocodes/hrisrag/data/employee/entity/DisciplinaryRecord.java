package com.mimocodes.hrisrag.data.employee.entity;

import java.time.LocalDate;

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

/** RESTRICTED data (HR_ADMIN, or the employee's direct manager). */
@Entity
@Table(name = "disciplinary_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DisciplinaryRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Employee employee;

	@Enumerated(EnumType.STRING)
	@Column(name = "action_type", nullable = false, length = 30)
	private DisciplinaryActionType actionType;

	@Column(name = "issued_on", nullable = false)
	private LocalDate issuedOn;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "issued_by_id")
	private Employee issuedBy;

	@Column(nullable = false, length = 500)
	private String reason;

	public DisciplinaryRecord(Employee employee, DisciplinaryActionType actionType, LocalDate issuedOn,
			Employee issuedBy, String reason) {
		this.employee = employee;
		this.actionType = actionType;
		this.issuedOn = issuedOn;
		this.issuedBy = issuedBy;
		this.reason = reason;
	}
}
