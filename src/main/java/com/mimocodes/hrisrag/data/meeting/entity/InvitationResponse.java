package com.mimocodes.hrisrag.data.meeting.entity;

/** Must match the CHECK constraint on meeting_invitation.response (V2). */
public enum InvitationResponse {
	PENDING, ACCEPTED, DECLINED, TENTATIVE
}
