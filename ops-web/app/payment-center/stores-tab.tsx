"use client";

// 支付门店（ME02）。行操作：查看（基本、归属记录、待处理变更）· 收款策略（抽屉）· 变更归属（抽屉，需另一人执行）
// · 撤销变更 / 执行变更（理由弹框）。支付方式偏好（逐方式开关）留到 P2：表单是逐项开关而不是字段，需要单独的编辑件。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { STORE_MODES, STORE_SOURCE_STATUSES, type StoreAssociation, type StoreRow } from "@/lib/pay-ops/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, joinCsv, options, reasonField, useRun, useStatusMap, useTabList, usePc, usePayCenterApi } from "./shared";

const NAME = "stores";

export function StoresTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const sourceMap = useStatusMap("source", STORE_SOURCE_STATUSES, { ACTIVE: "success", PENDING_SYNC: "warning", DELETED: "danger" });
  const modeMap = useStatusMap("mode", STORE_MODES, { OVERRIDE: "info" });
  const list = useTabList<StoreRow, { sourceStatus: string; configMode: string }>(NAME, { sourceStatus: "", configMode: "" }, (api, q) => api.stores.list(q));
  const { api } = list;
  const draft = (action: string, id: string) => (operator ? { operator, page: "pay-stores", action, id } : null);

  const actions: ActionSpec<StoreRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.storeDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.storeNo} load={() => api.stores.detail(ctx.row!.storeNo)}>
          {(d) => {
            const s = d.record.store;
            const p = d.record.pendingChange;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.storeNo, value: <PayRefCell value={s.storeNo} /> },
                    { label: c.storeName, value: s.storeName },
                    { label: c.colMerchant, value: s.merchantName },
                    { label: c.merchantNo, value: s.merchantNo },
                    { label: c.sourceStatus, value: <StatusBadge value={s.sourceStatus} map={sourceMap} /> },
                    { label: c.configMode, value: <StatusBadge value={s.configMode} map={modeMap} /> },
                    { label: c.payMerchantNos, value: joinCsv(s.payMerchantNos) || "—" },
                    { label: c.syncedAt, value: fmtTime(s.syncedAt) },
                    { label: c.syncIssue, value: s.syncIssue, full: true },
                  ] },
                  ...(p ? [{ title: c.sectionPending, fields: [
                    { label: c.targetMerchant, value: p.targetMerchantNo },
                    { label: c.requestedBy, value: p.requestedBy },
                    { label: c.submittedAt, value: fmtTime(p.requestedAt) },
                    { label: c.reasonField, value: p.reason, full: true },
                  ] }] : []),
                  { title: c.sectionAssociations, content: <MiniTable<StoreAssociation> rows={d.record.associations} rowKey={(r) => r.linkNo} empty={c.emptyAssociations} columns={[
                    { header: c.colMerchant, cell: (r) => r.merchantName ?? r.merchantNo },
                    { header: c.origin, cell: (r) => r.origin },
                    { header: c.validFrom, cell: (r) => fmtTime(r.validFrom) },
                    { header: c.validTo, cell: (r) => fmtTime(r.validTo) },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["policy", p ? "cancelChange" : "requestChange", ...(p ? ["executeChange"] : [])]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "policy", label: c.editPolicy, kind: "drawer", perm: "pay:collection-policy:manage", title: () => c.editPolicyTitle,
      render: (ctx) => <PolicyForm storeNo={ctx.row!.storeNo} draft={draft("policy", ctx.row!.storeNo)} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "requestChange", label: c.requestChange, kind: "drawer", perm: "pay:store:manage", title: () => c.requestChangeTitle,
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.requestChangeNote}</Notice>
          <ActionForm
          notify={false}
            fields={[{ key: "targetMerchantNo", label: c.targetMerchant, required: true, maxLength: 64 }, reasonField(c)]}
            initial={{ targetMerchantNo: "", reason: "" }}
            draft={draft("requestChange", ctx.row!.storeNo)}
            baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.stores.requestChange(ctx.row!.storeNo, { expectedVersion: ctx.row!.version, targetMerchantNo: String(v.targetMerchantNo).trim(), reason: String(v.reason) }), c.approvalSubmitted)}
            onDone={ctx.back ?? ctx.close}
          />
        </>
      ),
    },
    {
      key: "cancelChange", label: c.cancelChange, kind: "dialog", perm: "pay:store:manage",
      confirm: () => ({ title: c.cancelChangeTitle, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.stores.cancelChange(r!.storeNo, { expectedVersion: r!.version, reason })),
    },
    {
      key: "executeChange", label: c.executeChange, kind: "dialog", perm: "pay:store:approve",
      confirm: () => ({ title: c.executeChangeTitle, desc: c.executeChangeDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.stores.executeChange(r!.storeNo, { expectedVersion: r!.version, reason })),
    },
  ];

  const columns: Column<StoreRow>[] = [
    { header: c.storeNo, cell: (r) => <PayRefCell value={r.storeNo} /> },
    { header: c.storeName, cell: (r) => r.storeName },
    { header: c.colMerchant, cell: (r) => r.merchantName ?? "—" },
    { header: c.configMode, cell: (r) => <StatusBadge value={r.configMode} map={modeMap} /> },
    { header: c.sourceStatus, cell: (r) => <StatusBadge value={r.sourceStatus} map={sourceMap} /> },
    { header: c.syncedAt, cell: (r) => fmtTime(r.syncedAt) },
  ];

  return (
    <PayListPage<StoreRow>
      header={header}
      help={c.storesDesc}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.storesSearch}
      filters={<>
        <FilterSelect value={list.filters.sourceStatus} onChange={(v) => list.setFilter("sourceStatus", v)} options={sourceMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.configMode} onChange={(v) => list.setFilter("configMode", v)} options={modeMap} allLabel={c.configMode} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.storeNo}
      actions={actions}
      empty={c.emptyStores}
      resolve={(id) => api.stores.detail(id).then((d) => d.record.store)}
      draftPage="pay-stores"
    />
  );
}

/** 收款策略的版本在策略自己身上（不是门店的版本），所以先取策略再出表单 */
function PolicyForm({ storeNo, draft, onDone }: { storeNo: string; draft: { operator: string; page: string; action: string; id: string } | null; onDone: () => void }) {
  const c = usePc();
  const api = usePayCenterApi();
  const run = useRun(NAME);
  const modeMap = useStatusMap("mode", STORE_MODES);
  return (
    <DetailLoader name={NAME} id={`${storeNo}:policy`} load={() => api.stores.policy(storeNo)}>
      {(p) => (
        <ActionForm
          notify={false}
          fields={[
            { key: "mode", label: c.policyMode, type: "select", required: true, options: options(STORE_MODES, (v) => modeMap[v].label) },
            { key: "productCodes", label: c.policyProducts, help: c.policyProductsHelp, disabledWhen: (v) => v.mode !== "OVERRIDE" },
            reasonField(c),
          ]}
          initial={{ mode: p.mode, productCodes: joinCsv(p.productCodes), reason: "" }}
          draft={draft}
          baseVersion={p.version}
          isEdit
          onSubmit={(v) => run(() => api.stores.savePolicy(storeNo, { expectedVersion: p.version, mode: String(v.mode), productCodes: v.mode === "OVERRIDE" ? csv(v.productCodes) : [], reason: String(v.reason) }))}
          onDone={onDone}
        />
      )}
    </DetailLoader>
  );
}
