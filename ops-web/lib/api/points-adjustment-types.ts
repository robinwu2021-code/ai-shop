export type PointsAdjustmentSubmission = { requestId: string; adjustmentNo: string; typeCode: string; userNo: string; direction: "INCREASE" | "DECREASE"; points: number; reason: string };
export type PointsAdjustmentReview = { requestId: string; adjustmentNo: string; typeCode: string; expectedVersion: number; approve: boolean; reason: string };
export type PointsAdjustmentView = Omit<PointsAdjustmentSubmission, "requestId"> & { id: number; state: string; submittedBy: string; approvedBy: string | null; version: number; submittedAt?: number | null; reviewedAt?: number | null };
export type PointsAdjustmentPage = { rows: PointsAdjustmentView[]; next: number; hasMore: boolean };
