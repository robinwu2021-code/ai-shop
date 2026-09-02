"use client";

// 商家治理（矩阵 P-11.1）。**样板页 ①：列表 + 筛选 + 审核抽屉 + 权限降级 + 归档**。
// 新页面照这个骨架写：useQuery 取数 → Toolbar 筛选 → DataTable → Drawer 详情 → useMutation 写回。
import { Suspense, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
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
// 准入与保证金单独成块：它是「让不让他卖」那组决定，与档案/处置不同
import { AdmissionTab } from "./admission-tab";
// 门店档案（P-11.2.1）：主体的下一层实体，与「准入与保证金」里那份窄投影不是一回事
import { StoresTab } from "./stores-tab";
import { PlansTab } from "./plans-tab";
import { ModeRiskTab } from "./mode-risk-tab";
import { OnboardingTab } from "./onboarding-tab";
import { QualificationTab } from "./qualification-tab";
import { StaffBlock } from "./staff-block";
import { FulfillmentBlock } from "./fulfillment-block";
import { ArchiveActions, ShowArchivedToggle, archiveConfirm, archivedRowClass, unarchiveConfirm } from "@/components/archive";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
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

const TAB_KEYS = ["audit", "list", "stores", "categories", "qualifications", "verify", "credit", "admission", "onboarding", "mode-risk", "ban", "plans"] as const;

/** 入驻审核视图只看**还没走完审核**的那几档 —— 已通过/已封禁的属于档案，不该混在待办里。 */
const AUDIT_STATUSES = ["SUBMITTED", "REVIEWING"];

export default function MerchantsPage() {
  return <Suspense fallback={null}><MerchantsInner /></Suspense>;
}

function MerchantsInner() {
  const c = useCopy(MERCHANTS_COPY);
  const tabs = useNavTabs("/merchants", TAB_KEYS);
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
    {
      header: c.colLegalForm,
      /*
       * 准入档位完全由它决定：保证金、限额、禁售品类都按它取策略。
       * 不显示的话，运营看得到「这家被限额 500」却看不到「因为它是小微」——
       * 看得到结果看不到原因，只会引出一通电话。
       *
       * 自然人标 warning：它是唯一一档带硬限制的（无照 → 禁归集、限行业），一眼要能挑出来。
       */
      cell: (m) => (m.legalForm === "NATURAL_PERSON"
        ? <Badge tone="warning">{c.formMicro}</Badge>
        : m.legalForm === "INDIVIDUAL"
          ? <Badge tone="muted">{c.formIndividual}</Badge>
          : m.legalForm === "ENTERPRISE"
            ? <Badge tone="muted">{c.formEnterprise}</Badge>
            // 存量商家可能没有这个字段 —— 显示「未知」而不是空白，
            // 空白会被读成「企业」（最常见的那一档）
            : <span className="text-muted-foreground">{c.formUnknown}</span>),
    },
    {
      header: c.colFundsMode,
      /*
       * 资金路径（轴②）：钱先进谁的账户。
       *
       * 与上一列的「主体档位」放在一起是刻意的 —— **能走哪条由档位决定**，
       * 而不是平台自选。运营看不到它，就理解不了「为什么这家店改不了归集」。
       *
       * 直连标 info：它是尚未落地的那条（要 EDI + 收付通），一眼要能挑出来。
       */
      cell: (m) => (m.fundsMode === "DIRECT"
        ? <Badge tone="info">{c.fundsDirect}</Badge>
        : <Badge tone="muted">{c.fundsAggregated}</Badge>),
    },
    { header: c.colTier, cell: (m) => tierLabel(m.tier) },
    { header: c.colCommunity, cell: (m) => m.communityNos.join("、") },
    {
      header: c.colContact,
      // 两个字段都可能为空（老数据、或入驻时没填）——
      // 模板字符串会把 null 原样拼进去，列表里就出现一行「null null」
      cell: (m) => [m.contactName, m.contactPhone].filter(Boolean).join(" ") || "-",
    },
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
            <Button size="sm" variant="outline" onClick={() => { setCurrent(m); setRemark(m.auditRemark ?? ""); setCommunityNos(m.communityNos ?? []); }}>
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

      {tab === "stores" && <StoresTab c={c} />}

      {tab === "plans" && <PlansTab c={c} />}

      {tab === "credit" && <CreditTab c={c} />}

      {tab === "admission" && <AdmissionTab c={c} />}
      {tab === "qualifications" && <QualificationTab c={c} />}
      {tab === "mode-risk" && <ModeRiskTab c={c} />}
      {tab === "onboarding" && <OnboardingTab c={c} />}

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
              {/*
                受理 / 通过 / 驳回这三个动作属于申请单，不属于商家档案。
                它们曾经建在 `merchant.status` 上 —— 而那个字段是经营状态
                （能不能做生意），不是审核状态（这次申请审到哪了）。
                后端刻意分成两张表：一家已在正常经营、又提交了第二张执照的商家，
                在合成一个字段的模型里 status 该填什么无解，而「一人多主体」是常见情形。
                审核动作请到「入驻申请」页操作（/ops/merchant/apply/{no}/audit）。
              */}
              {current.status === "ACTIVE" && canVerify && (
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
              <Field className="mb-3" label={c.colStatus}><MerchantStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.fieldTier}>{tierLabel(current.tier)}</Field>
              <Field className="mb-3" label={c.colCommunity}>
                {/*
                  只读：选社区是审核动作的一部分（通过审核必须先选社区，ADR-009），
                  而审核已经归到申请单那边。在商家档案上再放一个可编辑的社区选择器，
                  等于给同一件事开两个入口，两边写的还不一定一致。
                */}
                {current.communityNos.join("、") || "-"}
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
              {current.auditRemark || "-"}
            </Field>
            {/* 履约配置：只读。为什么不给按钮见 fulfillment-block.tsx 顶部 */}
            <FulfillmentBlock merchantNo={current.merchantNo} />
            {/* 人员与授权：只读。为什么不给按钮见 staff-block.tsx 顶部 */}
            <StaffBlock merchantNo={current.merchantNo} />
            {/*
              门店入口（P-11.2.1a）。需求要两个入口：这里 + 独立检索页，
              **此前只有后者** —— 运营在商家详情里看不到这家有几个店，
              得另开一页再手输主体号筛。门店列表本身在那一页，这里只给一条路径过去，
              不在抽屉里再画一遍表。
            */}
            <Field label={c.fieldStores}>
              <Link href={`/merchants?tab=stores&merchantNo=${encodeURIComponent(current.merchantNo)}`}>
                <Button size="sm" variant="outline">{c.viewStores}</Button>
              </Link>
            </Field>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
