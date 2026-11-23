// OpsKit 审批接口的类型（TDD-OpsKit审批对接 §1.5）。按 OpsKit 仓库 docs/openapi/process.json、platform.json 手写，
// 只收页面用到的字段；OpsKit 只做加法（契约 §7），多出来的字段忽略即可。

export interface Named { code: string; name?: string; names?: Record<string, string> }
export interface Person { ref: string; displayName?: string }
export interface Subject { type: string; ref: string; display?: { title?: string; summary?: string } }

export interface Page<T> { list: T[]; page: number; size: number; total: number }

/** P1 */
export interface TodoCount { all: number; process: number; ticket: number; claimable: number }

/** P2 */
export interface TodoRow {
  taskNo: string; kind?: string; origin?: string; title?: string; subject?: Subject; process?: Named;
  approvalNo?: string; instanceNo?: string; createdAtMs?: number; dueAtMs?: number; claimable?: boolean;
}

/** 审批单状态（契约 §3.2 D2：审批单与执行是两回事，执行看实例状态） */
export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
export const APPROVAL_STATUSES: ApprovalStatus[] = ["PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

/** P3 */
export interface ApprovalRow {
  approvalNo: string; instanceNo?: string; process?: Named; subject?: Subject; status: ApprovalStatus | string;
  currentStep?: Named; round?: number; requestedBy?: Person; requestedAtMs?: number;
  stepStartedAtMs?: number; stepDueAtMs?: number; finishedAtMs?: number;
}

export interface ApprovalQuery {
  status?: string[]; processCode?: string; requestedBy?: string; keyword?: string; fromMs?: number; page: number; size: number;
}

export interface Step {
  code: string; name?: string; approverText?: string; conditionText?: string;
  state: "DONE" | "CURRENT" | "TODO" | "SKIPPED" | string; decidedBy?: string; decidedAtMs?: number;
}
export interface Round { round: number; status: string; atMs?: number; by?: string; comment?: string }
export interface Fact { label: string; value?: string; before?: string; hint?: string }
export interface SegmentAction { code: string; name?: string; requiresComment?: boolean }
export interface Segment {
  kind: "APPROVAL" | "ACTION" | string; code: string; name?: string; names?: Record<string, string>;
  state: "DONE" | "CURRENT" | "TODO" | "FAILED" | "REJECTED" | "ABANDONED" | "CANCELLED" | string;
  approvalNo?: string; error?: { code?: string; message?: string }; actions?: SegmentAction[];
}
export interface Mine { taskNo?: string; canDecide: boolean; whyNot?: string; canWithdraw: boolean }

/** P5 */
export interface ApprovalView extends ApprovalRow {
  facts?: Fact[]; steps?: Step[]; rounds?: Round[]; mine?: Mine; pipeline?: Segment[]; rules?: { crossStepExclusive?: boolean };
}

/** P9a（K1 / K2） */
export interface InstanceQuery {
  createdBy?: string; statusPrefix?: string; status?: string[]; statusCategory?: string[]; open?: boolean;
  processCode?: string; subjectType?: string; subjectRef?: string; page: number; size: number;
}
export interface InstanceRow {
  instanceNo: string; process?: Named; subject?: Subject; status?: Named; statusCategory?: string; open?: boolean;
  createdBy?: Person; createdAtMs?: number; updatedAtMs?: number; closedAtMs?: number;
}
export interface HistoryEntry {
  seq?: number; atMs?: number; action?: string; actionName?: string; actor?: Person | string; actorText?: string;
  from?: string; fromName?: string; to?: string; toName?: string; reason?: string;
}
/** 业务对象上的全部实例与审批（对账接口，页面用它从实例找到审批单） */
export interface SubjectHistory {
  type: string; ref: string;
  instances?: { instance: InstanceRow; approvals?: { approvalNo: string; status?: string; round?: number; requestedAtMs?: number }[] }[];
}

/** P10 */
export interface TemplateSummary {
  code: string; name?: string; names?: Record<string, string>; enabled: boolean; inherited?: boolean;
  running?: number; stepsSummary?: string; version?: string;
}
export type ApproverKind = "PERM" | "ROLE" | "GROUP" | "MEMBER" | "FIELD";
export interface TemplateStep {
  code: string; type?: "APPROVAL" | "ACTION"; name: Record<string, string>;
  approver?: { kind: ApproverKind; value: string | string[] }; mode?: "ANY" | "ALL";
  when?: { field: string; op: "TRUE" | "EQ" | "IN"; value?: string }; dueHours?: number | null;
  action?: { code: string; timeoutMinutes?: number };
}
/** P11 / P12 */
export interface Template {
  code: string; name: Record<string, string>; steps: TemplateStep[];
  rules?: { selfApproval?: boolean; crossStepExclusive?: boolean }; fields?: unknown[]; note?: string;
}
export interface TemplateVersion { version: string; atMs?: number; by?: string; channel?: string; current?: boolean; note?: string }
export interface SaveResult { version: string; created: boolean; runningOnOlderVersions?: number }

/** P16 */
export interface Me { memberId?: string; permissions?: string[]; externalPerms?: string[]; roles?: string[]; scopes?: string[] }
