"use client";

// 渠道进件（ME03）。行操作：查看（基本 + 资料提交记录）· 提交资料（抽屉，结算账号不进草稿）· 平台审核（抽屉，审核码）
// · 送件（抽屉）· 查询渠道进度（确认）· 撤销申请（理由弹框）。页面级：新建申请（抽屉）。
// 平台审核放在这一页而不是「审核待办」：它是 near-pay 进件域自己的复核（pay:onboarding:approve），不是 BM25 审批对象（见 TDD §6 偏差）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { ONBOARDING_STATUSES, REVIEW_STATUSES, type OnboardingRow, type OnboardingVersion } from "@/lib/pay-ops/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, reasonField, useRun, useStatusMap, useTabList, usePc } from "./shared";
import { WorkspaceHint } from "./merchants-tab";

const NAME = "onboardings";

export function OnboardingsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("ob", ONBOARDING_STATUSES, { APPROVED: "success", CHANNEL_REVIEW: "info", NEED_SIGN: "warning", REJECTED: "danger" });
  const reviewMap = useStatusMap("review", REVIEW_STATUSES, { APPROVED: "success", PENDING: "info", NEED_MORE: "warning", REJECTED: "danger" });
  const list = useTabList<OnboardingRow, { status: string; reviewStatus: string }>(NAME, { status: "", reviewStatus: "" }, (api, q) => api.onboardings.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-onboardings", action, id } : null);

  const actions: ActionSpec<OnboardingRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.onboardingDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.onboardingNo} load={() => api.onboardings.detail(ctx.row!.onboardingNo)}>
          {(d) => {
            const o = d.record.onboarding;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.onboardingNo, value: <PayRefCell value={o.onboardingNo} /> },
                    { label: c.colStatus, value: <StatusBadge value={o.status} map={statusMap} /> },
                    { label: c.colMerchant, value: o.merchantName ?? o.merchantNo },
                    { label: c.colChannel, value: o.payChannel },
                    { label: c.reviewStatus, value: <StatusBadge value={o.reviewStatus} map={reviewMap} /> },
                    { label: c.submitMode, value: o.submitMode },
                    { label: c.materialVersion, value: o.materialVersion },
                    { label: c.channelApplyNo, value: o.channelApplyNo },
                    { label: c.colUpdated, value: fmtTime(o.updatedAt) },
                    { label: c.rejectReason, value: o.rejectReason, full: true },
                  ] },
                  { title: c.sectionVersions, content: <MiniTable<OnboardingVersion> rows={d.record.versions} rowKey={(r) => String(r.versionIndex)} empty={c.emptyVersions} columns={[
                    { header: c.materialVersion, cell: (r) => r.versionIndex },
                    { header: c.reviewStatus, cell: (r) => <StatusBadge value={r.reviewStatus} map={reviewMap} /> },
                    { header: c.submittedBy, cell: (r) => r.submittedBy ?? "—" },
                    { header: c.submittedAt, cell: (r) => fmtTime(r.submittedAt) },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={d.allowedActions.filter((a) => a !== "manualResult" && a !== "retryHandoff")} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "submit", label: c.submitMaterials, kind: "drawer", perm: "pay:onboarding:manage", title: () => c.submitMaterialsTitle,
      visible: (r) => !!r && (r.status === "DRAFT" || r.reviewStatus === "NEED_MORE"),
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.submitMaterialsNote}</Notice>
          <ActionForm
          notify={false}
            fields={[
              { key: "legalForm", label: c.legalForm, required: true, maxLength: 64 },
              { key: "contactName", label: c.contactName, required: true, maxLength: 64 },
              { key: "contactPhone", label: c.contactPhone, required: true, maxLength: 32 },
              { key: "licenses", label: c.licenses, required: true, help: c.licensesHelp },
              { key: "settleAccountType", label: c.settleAccountType, required: true, maxLength: 32 },
              { key: "settleAccount", label: c.settleAccount, required: true, sensitive: true, help: c.settleAccountHelp },
            ]}
            initial={{ legalForm: "", contactName: "", contactPhone: "", licenses: "", settleAccountType: "", settleAccount: "" }}
            draft={draft("submit", ctx.row!.onboardingNo)}
            baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.onboardings.submit(ctx.row!.onboardingNo, {
              expectedVersion: ctx.row!.version, materialVersion: ctx.row!.materialVersion + 1,
              materials: { legalForm: String(v.legalForm), contactName: String(v.contactName), contactPhone: String(v.contactPhone), licenses: csv(v.licenses), settleAccountType: String(v.settleAccountType), settleAccount: String(v.settleAccount) },
            }))}
            onDone={ctx.back ?? ctx.close}
          />
        </>
      ),
    },
    {
      key: "review", label: c.reviewOnboarding, kind: "drawer", perm: "pay:onboarding:approve", title: () => c.reviewOnboardingTitle,
      visible: (r) => r?.reviewStatus === "PENDING",
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={[
            { key: "decision", label: c.decision, type: "select", required: true, options: [
              { value: "", label: "—" }, { value: "APPROVE", label: c.decisionApprove }, { value: "REJECT", label: c.decisionReject }, { value: "REQUEST_MORE", label: c.decisionMore },
            ] },
            reasonField(c),
          ]}
          initial={{ decision: "", reason: "" }}
          draft={draft("review", ctx.row!.onboardingNo)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.onboardings.review(ctx.row!.onboardingNo, { expectedVersion: ctx.row!.version, decision: v.decision as "APPROVE", reason: String(v.reason), materialVersion: ctx.row!.materialVersion }))}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "dispatch", label: c.dispatch, kind: "drawer", perm: "pay:onboarding:manage", title: () => c.dispatchTitle,
      visible: (r) => r?.reviewStatus === "APPROVED" && r.status === "SUBMITTED",
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={[{ key: "channelApplyNo", label: c.channelApplyNo, required: true, maxLength: 64 }, reasonField(c)]}
          initial={{ channelApplyNo: "", reason: "" }}
          draft={draft("dispatch", ctx.row!.onboardingNo)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.onboardings.dispatch(ctx.row!.onboardingNo, { expectedVersion: ctx.row!.version, channelApplyNo: String(v.channelApplyNo), reason: String(v.reason) }))}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "refresh", label: c.refresh, kind: "confirm", perm: "pay:onboarding:manage", visible: (r) => r?.status === "CHANNEL_REVIEW",
      confirm: () => ({ title: c.refreshTitle }),
      run: (r) => run(() => api.onboardings.refresh(r!.onboardingNo, { expectedVersion: r!.version, reason: c.refresh })),
    },
    {
      key: "cancel", label: c.cancelOnboarding, kind: "dialog", perm: "pay:onboarding:manage", danger: true,
      visible: (r) => !!r && ["DRAFT", "SUBMITTED"].includes(r.status),
      confirm: () => ({ title: c.cancelOnboardingTitle, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.onboardings.cancel(r!.onboardingNo, { expectedVersion: r!.version, reason })),
    },
  ];

  const pageActions: ActionSpec<OnboardingRow>[] = [{
    key: "create", label: c.createOnboarding, kind: "drawer", perm: "pay:onboarding:manage", title: () => c.createOnboardingTitle,
    render: (ctx) => (
      <ActionForm
          notify={false}
        fields={[{ key: "merchantNo", label: c.merchantNo, required: true, maxLength: 64 }, { key: "payChannel", label: c.payChannelField, required: true, maxLength: 32 }]}
        initial={{ merchantNo: "", payChannel: "" }}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.onboardings.create({ merchantNo: String(v.merchantNo).trim(), payChannel: String(v.payChannel).trim().toUpperCase() }))}
        onDone={ctx.close}
      />
    ),
  }];

  const columns: Column<OnboardingRow>[] = [
    { header: c.onboardingNo, cell: (r) => <PayRefCell value={r.onboardingNo} /> },
    { header: c.colMerchant, cell: (r) => r.merchantName ?? r.merchantNo },
    { header: c.colChannel, cell: (r) => r.payChannel },
    { header: c.reviewStatus, cell: (r) => <StatusBadge value={r.reviewStatus} map={reviewMap} /> },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colUpdated, cell: (r) => fmtTime(r.updatedAt) },
  ];

  return (
    <PayListPage<OnboardingRow>
      header={header}
      help={c.onboardingsDesc}
      notice={<WorkspaceHint label={c.submitMaterialsNote} button={c.oms} perm="pay:onboarding:manage" workspace="onboarding" />}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.onboardingsSearch}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.reviewStatus} onChange={(v) => list.setFilter("reviewStatus", v)} options={reviewMap} allLabel={c.reviewStatus} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.onboardingNo}
      actions={actions}
      empty={c.emptyOnboardings}
      pageActions={pageActions}
      resolve={(id) => api.onboardings.detail(id).then((d) => d.record.onboarding)}
      draftPage="pay-onboardings"
    />
  );
}
