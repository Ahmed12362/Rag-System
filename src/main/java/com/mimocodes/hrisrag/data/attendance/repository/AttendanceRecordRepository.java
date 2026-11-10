package com.mimocodes.hrisrag.data.attendance.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.attendance.entity.AttendanceRecord;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

	List<AttendanceRecord> findByEmployee_EmployeeCodeAndWorkDateBetweenOrderByWorkDate(String employeeCode,
			LocalDate from, LocalDate to);
}
