package com.mimocodes.hrisrag.data.employee.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.employee.entity.DisciplinaryRecord;

/** RESTRICTED: access only through a service that enforces HR_ADMIN / direct-manager authorization (M8). */
public interface DisciplinaryRecordRepository extends JpaRepository<DisciplinaryRecord, Long> {

	List<DisciplinaryRecord> findByEmployee_EmployeeCodeOrderByIssuedOnDesc(String employeeCode);
}
