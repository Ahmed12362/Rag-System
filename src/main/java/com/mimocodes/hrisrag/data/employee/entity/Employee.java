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

@Entity
@Table(name = "employee")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Stable business key used in questions, tools and benchmark labels, e.g. E-1001. */
	@Column(name = "employee_code", nullable = false, unique = true, length = 20)
	private String employeeCode;

	@Column(name = "full_name_en", nullable = false, length = 160)
	private String fullNameEn;

	@Column(name = "full_name_ar", nullable = false, length = 160)
	private String fullNameAr;

	@Column(nullable = false, unique = true, length = 200)
	private String email;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "department_id", nullable = false)
	private Department department;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "manager_id")
	private Employee manager;

	@Column(name = "job_title", nullable = false, length = 120)
	private String jobTitle;

	@Enumerated(EnumType.STRING)
	@Column(name = "employment_type", nullable = false, length = 20)
	private EmploymentType employmentType;

	@Column(name = "hire_date", nullable = false)
	private LocalDate hireDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EmployeeStatus status;

	public Employee(String employeeCode, String fullNameEn, String fullNameAr, String email, Department department,
			Employee manager, String jobTitle, EmploymentType employmentType, LocalDate hireDate, EmployeeStatus status) {
		this.employeeCode = employeeCode;
		this.fullNameEn = fullNameEn;
		this.fullNameAr = fullNameAr;
		this.email = email;
		this.department = department;
		this.manager = manager;
		this.jobTitle = jobTitle;
		this.employmentType = employmentType;
		this.hireDate = hireDate;
		this.status = status;
	}

	public void assignManager(Employee manager) {
		this.manager = manager;
	}
}
