package com.mimocodes.hrisrag.data.leave.entity;

/** Must match the CHECK constraint on leave_request.status (V2). */
public enum LeaveRequestStatus {
	PENDING, APPROVED, REJECTED, CANCELLED;

	/** APPROVED and REJECTED require a decision timestamp (CHECK leave_request_decision). */
	public boolean isDecided() {
		return this == APPROVED || this == REJECTED;
	}
}
