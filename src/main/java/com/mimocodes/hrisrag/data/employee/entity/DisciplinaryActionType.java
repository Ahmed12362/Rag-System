package com.mimocodes.hrisrag.data.employee.entity;

/** Must match the CHECK constraint on disciplinary_record.action_type (V2). */
public enum DisciplinaryActionType {
	VERBAL_WARNING, WRITTEN_WARNING, FINAL_WARNING, SUSPENSION, DEDUCTION
}
