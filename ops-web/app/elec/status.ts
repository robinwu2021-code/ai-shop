// 元器件几个状态的「码 → 文案 + 色调」。键序即筛选下拉里的顺序。
import type { StatusMap } from "@/components/ui/status-badge";
import type { ElecDispatchStatus, ElecOpsQuoteStatus, ElecRfqStatus, ElecSupplierStatus } from "@/lib/types";
import type { ElecCopy } from "./copy";

export const rfqStatusMap = (c: ElecCopy): StatusMap<ElecRfqStatus> => ({
  SUBMITTED: { label: c.stSUBMITTED, tone: "warning" },
  QUOTED: { label: c.stQUOTED, tone: "info" },
  EXPIRED: { label: c.stEXPIRED, tone: "muted" },
  ACCEPTED: { label: c.stACCEPTED, tone: "success" },
  CLOSED: { label: c.stCLOSED, tone: "muted" },
});

export const supplierStatusMap = (c: ElecCopy): StatusMap<ElecSupplierStatus> => ({
  PENDING: { label: c.supPENDING, tone: "warning" },
  ACTIVE: { label: c.supACTIVE, tone: "success" },
  SUSPENDED: { label: c.supSUSPENDED, tone: "danger" },
});

export const dispatchStatusMap = (c: ElecCopy): StatusMap<ElecDispatchStatus> => ({
  SENT: { label: c.dsSENT, tone: "muted" },
  VIEWED: { label: c.dsVIEWED, tone: "warning" },
  QUOTED: { label: c.dsQUOTED, tone: "success" },
  DECLINED: { label: c.dsDECLINED, tone: "muted" },
});

export const quoteStatusMap = (c: ElecCopy): StatusMap<ElecOpsQuoteStatus> => ({
  ACTIVE: { label: c.qsACTIVE, tone: "info" },
  WITHDRAWN: { label: c.qsWITHDRAWN, tone: "muted" },
  ACCEPTED: { label: c.qsACCEPTED, tone: "success" },
  NOT_CHOSEN: { label: c.qsNOT_CHOSEN, tone: "muted" },
  EXPIRED: { label: c.qsEXPIRED, tone: "muted" },
});
