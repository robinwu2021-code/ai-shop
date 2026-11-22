"use client";

// 商户详情的 P8 区块（TDD-支付中心运营页面 §2.15）：资料版本（GET /merchants/{no}/profile-revisions，带审核人与意见）、
// 工作台（GET /merchants/{no}/workspace：near-pay 各板块是否开放与数量）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import type { DetailSection } from "@/components/ui/detail-drawer";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { PROFILE_REVIEW_STATUSES, type ProfileRevisionRow, type WorkspaceSection } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, type C } from "./shared";
import type { P8Copy } from "./p8-copy";
import { p8Label, p8Map } from "./members-tab";

const NAME = "merchants";

/** 摘要是 near-pay 给的 JSON 字符串：展开成「键 值」短句，解析不了就原样显示 */
export function summaryText(json: string | null): string {
  if (!json) return "—";
  try {
    const v = JSON.parse(json) as unknown;
    if (v && typeof v === "object" && !Array.isArray(v)) {
      return Object.entries(v as Record<string, unknown>).map(([k, x]) => `${k} ${typeof x === "object" ? JSON.stringify(x) : String(x)}`).join("；");
    }
    return String(v);
  } catch {
    return json;
  }
}

export function merchantP8Sections(api: PayCenterApi, no: string, c: C, m: P8Copy): DetailSection[] {
  const revMap = p8Map(m, "st_rev", PROFILE_REVIEW_STATUSES, { APPROVED: "success", SUBMITTED: "warning", REJECTED: "danger", NEED_MORE: "warning" });
  const wsMap = p8Map(m, "st_ws", ["READY", "NOT_READY"], { READY: "success" });
  return [
    { title: m.sectionRevisions, content: (
      <DetailLoader name={NAME} id={`${no}:revisions`} load={() => api.merchants.revisions(no)}>
        {(rows) => <MiniTable<ProfileRevisionRow> rows={rows} rowKey={(r) => r.revisionNo} empty={m.revEmpty} columns={[
          { header: m.revision, cell: (r) => r.revision },
          { header: m.purpose, cell: (r) => p8Label(m, "purpose", r.purpose) },
          { header: m.reviewStatus, cell: (r) => <StatusBadge value={r.reviewStatus} map={revMap} /> },
          { header: m.submitted, cell: (r) => `${r.submittedBy} · ${fmtTime(r.submittedAt)}` },
          { header: m.reviewed, cell: (r) => (r.reviewedBy ? `${r.reviewedBy} · ${fmtTime(r.reviewedAt)}` : "—") },
          { header: m.reviewReason, cell: (r) => r.reviewReason ?? "—" },
          { header: m.approval, cell: (r) => r.approvalNo ?? "—" },
        ]} />}
      </DetailLoader>
    ) },
    { title: m.sectionWorkspace, content: (
      <DetailLoader name={NAME} id={`${no}:workspace`} load={() => api.merchants.workspace(no)}>
        {(w) => <MiniTable<WorkspaceSection> rows={w.sections} rowKey={(r) => r.key} empty={m.wsEmpty} columns={[
          { header: m.wsSection, cell: (r) => r.title },
          { header: m.wsModule, cell: (r) => r.module },
          { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={wsMap} /> },
          { header: m.wsCount, cell: (r) => r.count ?? "—" },
          { header: m.wsSummary, cell: (r) => summaryText(r.summaryJson) },
        ]} />}
      </DetailLoader>
    ) },
  ];
}
