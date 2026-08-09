"use client";

// 商家治理（矩阵 P-11.1）。**样板页 ①：列表 + 筛选 + 审核抽屉 + 权限降级 + 归档**。
// 新页面照这个骨架写：useQuery 取数 → Toolbar 筛选 → DataTable → Drawer 详情 → useMutation 写回。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { usePageTab } from "@/lib/use-page-tab";
import { usePaging } from "@/lib/use-paging";
import { useCopy } from "@/lib/use-copy";
import { MERCHANTS_COPY } from "./copy";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Merchant, MerchantStatus } from "@/lib/types";
import { MerchantStatusBadge, VerifiedBadge, useMerchantStatusMap, useMerchantTierLabel } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 授权/认证标与信用/处置各自成块 —— 与审核那两个 tab 只共用文案表
import { ApplyTab } from "./apply-tab";
import { CategoryTab, VerifyTab } from "./authorize-tab";
import { BanTab, CreditTab } from "./credit-tab";
import { ArchiveActions, ShowArchivedToggle, archiveConfirm, archivedRowClass, unarchiveConfirm } from "@/components/archive";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { MultiSelect } from "@/components/ui/multi-select";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { Textarea } from "@/components/ui/textarea";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof MERCHANTS_COPY)["zh"];
const TIER_OPTIONS = (c: Copy) => [
  { value: "PERSONAL", label: c.tierPersonal },
  { value: "INDIVIDUAL", label: c.tierIndividual },
  { value: "COMPANY", label: c.tierCompany },
];

const TABS = (c: Copy) => [
  { key: "audit", label: c.tabAudit },
  { key: "list", label: c.tabList },
  { key: "categories", label: c.tabCategories },
  { key: "verify", label: c.tabVerify },
  { key: "credit", label: c.tabCredit },
  { key: "ban", label: c.tabBan },
];

/** 入驻审核视图只看**还没走完审核**的那几档 —— 已通过/已封禁的属于档案，不该混在待办里。 */
const AUDIT_STATUSES = ["SUBMITTED", "REVIEWING"];

export default function MerchantsPage() {
  return <Suspense fallback={null}><MerchantsInner /></Suspense>;
}

function MerchantsInner() {
  const c = useCopy(MERCHANTS_COPY);
  const tabs = TABS(c);
  const tierOptions = TIER_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();
  const statusMap = useMerchantStatusMap();
  const tierLabel = useMerchantTierLabel();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); setTier(""); setShowArchived(false); });

  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [tier, setTier] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<Merchant | null>(null);
  const [remark, setRemark] = useState("");
  /** 通过审核时要指定的覆盖社区（ADR-009）。不给的话商家对买家不可见 */
  const [communityNos, setCommunityNos] = useState<string[]>([]);

  const canAudit = allow("merchant:apply:audit");
  const canVerify = allow("merchant:verify:grant");
  const canGrantCat = allow("merchant:category:grant");
  const canBan = allow("merchant:merchant:ban");

  // 审核视图：没选具体状态时只带出待审的两档（选了就按选的来，筛选优先于视图默认）
  const q = {
    keyword, tier, showArchived, page, size,
    status: status || (tab === "audit" ? AUDIT_STATUSES.join(",") : ""),
  };
  const list = useQuery({
    queryKey: ["merchants", q],
    queryFn: () => api.listMerchants(q),
  });

  // 写操作统一走这里：失败的 toast 由 Providers 的 mutationCache 兜住，不用逐处 catch。
  const invalidate = () => qc.invalidateQueries({ queryKey: ["merchants"] });

  // 社区目录：审核时要从这里挑。只取启用中的，归档的小区不该还能被指派
  const communities = useQuery({
    queryKey: ["communities", "for-audit"],
    queryFn: () => api.listCommunities({ page: 1, size: 200 }),
  });

  const setStatusMut = useMutation({
    mutationFn: (v: { merchantNo: string; status: MerchantStatus; remark?: string; communityNos?: string[] }) =>
      api.setMerchantStatus(v.merchantNo, v.status, v.remark, v.communityNos),
    onSuccess: (m) => {
      invalidate();
      setCurrent(m);
      notify.success(c.toastStatusSaved);
    },
  });

  const verifyMut = useMutation({
    mutationFn: (v: { merchantNo: string; verified: boolean }) => api.setMerchantVerified(v.merchantNo, v.verified),
    onSuccess: (m) => {
      invalidate();
      setCurrent(m);
      notify.success(m.verified ? c.toastVerifyGranted : c.toastVerifyRevoked);
    },
  });

  const archiveMut = useMutation({
    mutationFn: (v: { merchantNo: string; restore: boolean }) =>
      v.restore ? api.unarchiveMerchant(v.merchantNo) : api.archiveMerchant(v.merchantNo),
    onSuccess: invalidate,
  });

  const columns: Column<Merchant>[] = [
    { header: c.colNo, cell: (m) => m.merchantNo, numeric: true, align: "start" },
    {
      header: c.colName,
      cell: (m) => (
        <div className="flex items-center gap-2">
          <span className="truncate">{m.name}</span>
          <VerifiedBadge verified={m.verified} />
        </div>
      ),
    },
    { header: c.colTier, cell: (m) => tierLabel(m.tier) },
    { header: c.colCommunity, cell: (m) => m.communityName },
    { header: c.colContact, cell: (m) => `${m.contactName} ${m.contactPhone}` },
    { header: c.colStatus, cell: (m) => <MerchantStatusBadge value={m.status} /> },
    // 分账接收方未报备 = 结算走不通（ADR-002），列表就要能看出来，别等到打款那天
    { header: c.colSettleAccount, cell: (m) => (m.settleAccountReady ? c.settleReady : <span className="text-[var(--warning)]">{c.settleNotReady}</span>) },
    { header: c.colCreatedAt, cell: (m) => fmtTime(m.createdAt) },
    {
      header: c.colActions,
      cell: (m) => (
        <ArchiveActions
          archived={!!m.archivedAt}
          canWrite={canAudit}
          onArchive={async () => {
            await confirm(archiveConfirm(c.entity, m.name, m.merchantNo, () => archiveMut.mutateAsync({ merchantNo: m.merchantNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(c.entity, m.name, () => archiveMut.mutateAsync({ merchantNo: m.merchantNo, restore: true })));
          }}
          actions={
            <Button size="sm" variant="outline" onClick={() => { setCurrent(m); setRemark(m.auditRemark ?? ""); setCommunityNos(m.communityNo ? [m.communityNo] : []); }}>
              {c.actionView}
            </Button>
          }
        />
      ),
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "audit" && (
        <>
          {!canAudit && (
            <ReadOnlyNotice what={c.readOnlyWhat} perm="merchant:apply:audit" note={c.readOnlyNote} className="mb-3" />
          )}
          {/*
            审核走「申请单」模型（已接真后端）；下面的 list tab 仍是商家档案。
            两者不是同一个资源：通过之前商家不存在，所以审核动作打在 applyNo 上。
          */}
          <ApplyTab c={c} canAudit={canAudit} />
        </>
      )}

      {tab === "categories" && (
        <>
          {!canGrantCat && <ReadOnlyNotice what={c.authorizeReadOnlyWhat} perm="merchant:category:grant" className="mb-3" />}
          <CategoryTab c={c} canGrant={canGrantCat} />
        </>
      )}

      {tab === "verify" && (
        <>
          {!canVerify && <ReadOnlyNotice what={c.authorizeReadOnlyWhat} perm="merchant:verify:grant" className="mb-3" />}
          <VerifyTab c={c} canGrant={canVerify} />
        </>
      )}

      {tab === "credit" && <CreditTab c={c} />}

      {tab === "ban" && (
        <>
          {!canBan && <ReadOnlyNotice what={c.banReadOnlyWhat} perm="merchant:merchant:ban" className="mb-3" />}
          <BanTab c={c} canBan={canBan} />
        </>
      )}

      {tab === "list" && (
      <>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={c.searchPlaceholder}
      >
        <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
        <FilterSelect aria-label={c.filterTier} value={tier} onChange={(v) => { setTier(v); setPage(1); }} options={tierOptions} allLabel={c.filterTierAll} />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(m) => m.merchantNo}
        rowClassName={archivedRowClass}
        empty={c.empty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />
      </>
      )}

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.name ?? ""}
        desc={current?.merchantNo}
        footer={
          current && canAudit ? (
            <>
              {/* 按钮只出**当前状态允许的迁移**（合法迁移表见 lib/types/merchant.ts）——
                  出一个点了必报错的按钮，比不出更糟。 */}
              {current.status === "SUBMITTED" && (
                <Button onClick={() => setStatusMut.mutate({ merchantNo: current.merchantNo, status: "REVIEWING" })}>
                  {c.btnStartReview}
                </Button>
              )}
              {current.status === "REVIEWING" && (
                <>
                  <Button
                    variant="outline"
                    onClick={() => setStatusMut.mutate({ merchantNo: current.merchantNo, status: "REJECTED", remark })}
                  >
                    {c.btnReject}
                  </Button>
                  <Button
                  disabled={!communityNos.length}
                  onClick={() => setStatusMut.mutate({
                    merchantNo: current.merchantNo,
                    status: "APPROVED",
                    remark: "",
                    communityNos,
                  })}
                >
                    {c.btnApprove}
                  </Button>
                </>
              )}
              {current.status === "APPROVED" && canVerify && (
                <Button
                  variant="outline"
                  onClick={() => verifyMut.mutate({ merchantNo: current.merchantNo, verified: !current.verified })}
                >
                  {current.verified ? c.btnRevokeVerify : c.btnGrantVerify}
                </Button>
              )}
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.fieldAuditStatus}><MerchantStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.fieldTier}>{tierLabel(current.tier)}</Field>
              <Field className="mb-3" label={c.colCommunity}>
                {canAudit && current.status === "REVIEWING" ? (
                  /*
                   * 通过审核必须先选社区（ADR-009）。做成审核动作的一部分而不是
                   * 事后再配 —— 分两步做永远有人忘，而忘了的后果是商家上着架
                   * 却一个订单都不来，且没有任何报错。
                   */
                  <div>
                    <MultiSelect
                      value={communityNos}
                      options={(communities.data?.records ?? []).map((cm) => ({
                        value: cm.communityNo,
                        label: cm.name,
                      }))}
                      onChange={setCommunityNos}
                      invalid={!communityNos.length}
                    />
                    {!communityNos.length && (
                      <p className="mt-1 text-xs text-danger">{c.communityRequired}</p>
                    )}
                  </div>
                ) : (
                  current.communityName || "-"
                )}
              </Field>
              <Field className="mb-3" label={c.colContact}>{current.contactName}</Field>
              {/* 手机号在平台端也只展示掩码：完整号码属于越权边界（矩阵 §2.3 / M11） */}
              <Field className="mb-3" label={c.fieldPhone}>{current.contactPhone}</Field>
              <Field className="mb-3" label={c.fieldBreach}>{current.breachCount}</Field>
              <Field className="mb-3" label={c.colSettleAccount}>{current.settleAccountReady ? c.settleReady : c.settleNotReady}</Field>
              <Field className="mb-3" label={c.fieldPickupIntent}>
                {current.asPickupPoint ? c.pickupWanted : c.pickupNo}
              </Field>
              <Field className="mb-3" label={c.colCreatedAt}>{fmtTime(current.createdAt)}</Field>
            </FieldGrid>
            <Field label={c.fieldCategories}>{current.categoryCodes.join("、") || "-"}</Field>
            <Field label={c.fieldRemark}>
              {canAudit && current.status === "REVIEWING" ? (
                <Textarea
                  value={remark}
                  onChange={setRemark}
                  placeholder={c.remarkPlaceholder}
                />
              ) : (
                current.auditRemark || "-"
              )}
            </Field>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
