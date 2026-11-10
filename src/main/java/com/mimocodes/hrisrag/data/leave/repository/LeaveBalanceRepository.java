package com.mimocodes.hrisrag.data.leave.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.leave.entity.LeaveBalance;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

	/** Backs the "remaining leave for employee X" tool (M8). */
	Optional<LeaveBalance> findByEmployee_EmployeeCodeAndLeaveType_CodeAndYear(String employeeCode,
			String leaveTypeCode, short year);

	List<LeaveBalance> findByEmployee_EmployeeCodeAndYear(String employeeCode, short year);
}
