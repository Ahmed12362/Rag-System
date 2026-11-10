package com.mimocodes.hrisrag.data.employee.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.employee.entity.Employee;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

	Optional<Employee> findByEmployeeCode(String employeeCode);

	/** Direct reports, used for manager-scoped authorization (M8). */
	List<Employee> findByManager_EmployeeCode(String managerEmployeeCode);
}
