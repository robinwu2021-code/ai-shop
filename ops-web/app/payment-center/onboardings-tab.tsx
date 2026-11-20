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
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { ONBOARDING_STATUSES, REVIEW_STATUSES, type OnboardingRow, type OnboardingVersion } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, reasonField, useRun, useStatusMap, useTabList, usePc } from "./shared";
import { onboardingCan, onboardingExtraSections, useOnboardingPageActions, useOnboardingRowActions, useOws } from "./onboarding-workspace";
import { usesDraft } from "@/app/payment-center/_api/onboarding-draft";

const NAME = "onboardings";
/** 详情底栏：按可见与权限再过滤（onboardingCan / draftEditable），这里只定顺序 */
const DETAIL_ACTIONS = ["cancel", "platform", "nwp-identity", "nwp-contact", "nwp-store", "nwp-settlement", "nwp-ops", "attachments", "recognize", "confirmDraft", "submitDraft",
  "submit", "submitReview", "review", "requestFields", "dispatch", "manualResult", "refresh", "retryHandoff", "modifications"];

export function OnboardingsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("ob", ONBOARDING_STATUSES, { APPROVED: "success", CHANNEL_REVIEW: "info", NEED_SIGN: "warning", REJECTED: "danger" });
  const reviewMap = useStatusMap("review", REVIEW_STATUSES, { APPROVED: "success", PENDING: "info", NEED_MORE: "warning", REJECTED: "danger" });
  const list = useTabList<OnboardingRow, { status: string; reviewStatus: string }>(NAME, { status: "", reviewStatus: "" }, (api, q) => api.onboardings.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-onboardings", action, id } : null);
  const ow = useOws();
  const workspace = useOnboardingRowActions(api, run);

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
                  ...onboardingExtraSections(api, d, c, ow),
                ]}
                actions={<DetailActions ctx={ctx} keys={DETAIL_ACTIONS} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      // 非蓝新渠道仍一次提交材料；蓝新开了草稿后 near-pay 拒绝直接提交，走草稿操作（onboarding-workspace.tsx）
      key: "submit", label: c.submitMaterials, kind: "drawer", perm: "pay:onboarding:manage", title: () => c.submitMaterialsTitle,
      visible: (r) => onboardingCan(r, "submit") && !!r && !usesDraft(r),
      render: (ctx) => (
        <>
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
      key: "refresh", label: c.refresh, kind: "confirm", perm: "pay:onboarding:manage", visible: (r) => onboardingCan(r, "refresh"),
      confirm: () => ({ title: c.refreshTitle }),
      run: (r) => run(() => api.onboardings.refresh(r!.onboardingNo, { expectedVersion: r!.version, reason: c.refresh })),
    },
    {
      key: "cancel", label: c.cancelOnboarding, kind: "dialog", perm: "pay:onboarding:manage", danger: true,
      visible: (r) => onboardingCan(r, "cancel"),
      confirm: () => ({ title: c.cancelOnboardingTitle, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.onboardings.cancel(r!.onboardingNo, { expectedVersion: r!.version, reason })),
    },
  ];

  const pageActions = useOnboardingPageActions(api, run);


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
      actions={[...actions, ...workspace]}
      empty={c.emptyOnboardings}
      pageActions={pageActions}
      resolve={(id) => api.onboardings.detail(id).then((d) => d.record.onboarding)}
      draftPage="pay-onboardings"
    />
  );
}
