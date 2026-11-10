package com.mimocodes.hrisrag.data.employee.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.employee.entity.Department;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

	Optional<Department> findByCode(String code);
}
