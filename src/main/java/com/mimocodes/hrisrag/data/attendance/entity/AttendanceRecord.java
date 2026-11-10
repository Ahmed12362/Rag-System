package com.mimocodes.hrisrag.data.attendance.entity;

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
@Table(name = "attendance_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Employee employee;

	@Column(name = "work_date", nullable = false)
	private LocalDate workDate;

	@Column(name = "check_in")
	private Instant checkIn;

	@Column(name = "check_out")
	private Instant checkOut;

	@Column(name = "minutes_late", nullable = false)
	private int minutesLate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AttendanceStatus status;

	public AttendanceRecord(Employee employee, LocalDate workDate, Instant checkIn, Instant checkOut, int minutesLate,
			AttendanceStatus status) {
		this.employee = employee;
		this.workDate = workDate;
		this.checkIn = checkIn;
		this.checkOut = checkOut;
		this.minutesLate = minutesLate;
		this.status = status;
	}
}
