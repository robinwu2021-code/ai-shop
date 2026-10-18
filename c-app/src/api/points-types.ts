export interface PointsOrderState {
  phase: "NONE" | "RAW" | "ADMITTED" | "UNKNOWN" | "READY" | "RECONFIRM" | "CANCEL_REQUESTED" | "CANCELLED";
  reason: string | null;
}

export interface OrderPointsQuote {
  state: "AVAILABLE" | "UNAVAILABLE";
  reason: string | null;
  pointsUsed: number;
  deductMinor: number;
  ratePoints: number;
  rateMinor: number;
  basePayMinor: number;
  stores: { storeNo: string; merchantNo: string; policyVersion: number;
    pointsUsed: number; deductMinor: number }[];
}

export interface BuyerPointsAccount {
  typeCode: string;
  market: string;
  currency: string;
  status: "DRAFT" | "ACTIVE" | "DRAINING" | "RETIRED";
  balance: number;
  pendingBalance: number;
  heldBalance: number;
  ratePoints: number;
  rateMinor: number;
  deductRatioBp: number;
  idleDays: number;
  frozen: boolean;
  expireAt: number | null;
}

export interface BuyerPointsEntry {
  entryNo: string;
  bucket: "AVAILABLE" | "PENDING" | "HELD";
  points: number;
  balanceAfter: number;
  at: number;
}

export interface BuyerPointsEntryPage {
  rows: BuyerPointsEntry[];
  next: number;
  hasMore: boolean;
}

