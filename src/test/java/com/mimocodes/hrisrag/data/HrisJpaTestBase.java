package com.mimocodes.hrisrag.data;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.mimocodes.hrisrag.TestcontainersConfiguration;
import com.mimocodes.hrisrag.data.employee.entity.Department;
import com.mimocodes.hrisrag.data.employee.entity.Employee;
import com.mimocodes.hrisrag.data.employee.entity.EmployeeStatus;
import com.mimocodes.hrisrag.data.employee.entity.EmploymentType;
import com.mimocodes.hrisrag.data.employee.repository.DepartmentRepository;
import com.mimocodes.hrisrag.data.employee.repository.EmployeeRepository;
import com.mimocodes.hrisrag.data.leave.entity.LeaveType;
import com.mimocodes.hrisrag.data.leave.repository.LeaveTypeRepository;

/**
 * JPA slice against the real Flyway schema in a pgvector Testcontainer (no embedded DB replacement).
 * Each test runs in a transaction that is rolled back. Hibernate runs with ddl-auto=validate, so a mapping that
 * does not match V2 fails context startup.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
public abstract class HrisJpaTestBase {

	@Autowired
	protected DepartmentRepository departments;

	@Autowired
	protected EmployeeRepository employees;

	@Autowired
	protected LeaveTypeRepository leaveTypes;

	protected Department department(String code) {
		return departments.save(new Department(code, code + " (en)", code + " (ar)"));
	}

	protected Employee employee(String code, Department department, Employee manager) {
		return employees.save(new Employee(code, "Name " + code, "اسم " + code, code.toLowerCase() + "@example.test",
				department, manager, "Engineer", EmploymentType.FULL_TIME, LocalDate.of(2022, 1, 10),
				EmployeeStatus.ACTIVE));
	}

	protected LeaveType leaveType(String code, int defaultDays) {
		return leaveTypes.save(new LeaveType(code, code + " leave", "إجازة " + code, BigDecimal.valueOf(defaultDays)));
	}
}
