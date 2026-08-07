"use client";

// 营销活动（矩阵 P-7.1 券 / P-7.2 活动 / P-7.3 内容位）。
//
// 四类活动（秒杀/限时/满减/买赠/新人礼包）合成**一张表**用 type 区分：字段结构一致，
// 拆五张表会让「同一时段有哪些活动在跑」这个最常问的问题查不出来（TDD-ops-营销与评价 §2.1）。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { MARKETING_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Campaign, ContentSlot, Coupon, CouponIssue, CouponStatus, IssueTarget } from "@/lib/types";
import {
  CampaignStatusBadge, CouponStatusBadge, useCampaignStatusMap, useCampaignTypeMap,
  useCouponStatusMap, useCouponTypeMap, useSlotKindMap,
} from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 会员卡自成一块 —— 与券/活动/内容位三个 tab 只共用文案表
import { MemberTab } from "./member-tab";
import { ArchiveActions, ShowArchivedToggle, archiveConfirm, archivedRowClass, unarchiveConfirm } from "@/components/archive";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Progress } from "@/components/ui/progress";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useI18n } from "@/lib/i18n";

type Copy = (typeof MARKETING_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "coupons", label: c.tabCoupons },
  { key: "issues", label: c.tabIssues },
  { key: "campaigns", label: c.tabCampaigns },
  { key: "slots", label: c.tabSlots },
  { key: "member", label: c.tabMember },
];

const TARGET_OPTIONS = (c: Copy): { value: IssueTarget; label: string }[] => [
  { value: "ALL", label: c.targetAll },
  { value: "NEW_USER", label: c.targetNewUser },
  { value: "COMMUNITY", label: c.targetCommunity },
  { value: "SINGLE_USER", label: c.targetSingleUser },
];

/** 券面额展示：折扣券存的是万分比（8500 = 85 折），与满减的"分"不是一回事。 */
function couponValue(coupon: Coupon, c: Copy) {
  return coupon.type === "DISCOUNT" ? fill(c.discountValue, { n: (coupon.value / 1000).toFixed(1) }) : money(coupon.value);
}

export default function MarketingPage() {
  return <Suspense fallback={null}><MarketingInner /></Suspense>;
}

function MarketingInner() {
  const c = useCopy(MARKETING_COPY);
  const tabs = TABS(c);
  const targetOptions = TARGET_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setType(""); setStatus(""); setShowArchived(false); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [kind, setKind] = useState("");
  const [showArchived, setShowArchived] = useState(false);

  const [issuing, setIssuing] = useState<Coupon | null>(null);
  const [issueForm, setIssueForm] = useState<{ target: IssueTarget; targetDesc: string; count: string }>({
    target: "ALL", targetDesc: "", count: "100",
  });
  const [budgetEdit, setBudgetEdit] = useState<{ couponNo: string; value: string } | null>(null);

  const canIssue = allow("marketing:coupon:issue");
  const canEditCampaign = allow("marketing:campaign:update");
  const canEditSlot = allow("marketing:slot:update");
  const canEditMember = allow("marketing:member:update");

  const couponTypeMap = useCouponTypeMap();
  const couponStatusMap = useCouponStatusMap();
  const campaignTypeMap = useCampaignTypeMap();
  const campaignStatusMap = useCampaignStatusMap();
  const slotKindMap = useSlotKindMap();

  const couponQ = { keyword, type, status, showArchived, page, size };
  const coupons = useQuery({ queryKey: ["coupons", couponQ], queryFn: () => api.listCoupons(couponQ), enabled: tab === "coupons" });
  const issueQ = { keyword, page, size };
  const issues = useQuery({ queryKey: ["coupon-issues", issueQ], queryFn: () => api.listCouponIssues(issueQ), enabled: tab === "issues" });
  const campaignQ = { keyword, type, status, showArchived, page, size };
  const campaigns = useQuery({ queryKey: ["campaigns", campaignQ], queryFn: () => api.listCampaigns(campaignQ), enabled: tab === "campaigns" });
  const slotQ = { keyword, kind, showArchived, page, size };
  const slots = useQuery({ queryKey: ["slots", slotQ], queryFn: () => api.listContentSlots(slotQ), enabled: tab === "slots" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["coupons"] });
    qc.invalidateQueries({ queryKey: ["coupon-issues"] });
    qc.invalidateQueries({ queryKey: ["campaigns"] });
    qc.invalidateQueries({ queryKey: ["slots"] });
  };

  const statusMut = useMutation({
    mutationFn: (v: { couponNo: string; status: CouponStatus }) => api.setCouponStatus(v.couponNo, v.status),
    onSuccess: () => { invalidate(); notify.success(c.toastCouponStatus); },
  });
  const budgetMut = useMutation({
    mutationFn: (v: { couponNo: string; budget: number }) => api.setCouponBudget(v.couponNo, v.budget),
    onSuccess: () => { invalidate(); setBudgetEdit(null); notify.success(c.toastBudgetSaved); },
  });
  const issueMut = useMutation({
    mutationFn: () =>
      api.issueCoupon({
        couponNo: issuing!.couponNo,
        target: issueForm.target,
        targetDesc: issueForm.targetDesc || targetOptions.find((o) => o.value === issueForm.target)!.label,
        count: Number(issueForm.count),
      }),
    onSuccess: (r) => { invalidate(); setIssuing(null); notify.success(fill(c.toastIssued, { n: r.count, amount: money(r.amount) })); },
  });
  const slotEnableMut = useMutation({
    mutationFn: (v: { slotNo: string; enabled: boolean }) => api.setSlotEnabled(v.slotNo, v.enabled),
    onSuccess: () => { invalidate(); notify.success(c.toastSlotStatus); },
  });
  // 三种实体共用一个 mutation：归档语义完全一致，分成三个 hook 只是三份同样的样板。
  // 返回类型显式收成 unknown —— 调用方只关心"成功了"，不需要那条记录。
  const archiveMut = useMutation<unknown, Error, { kind: "coupon" | "campaign" | "slot"; no: string; restore: boolean }>({
    mutationFn: (v) =>
      v.kind === "coupon"
        ? (v.restore ? api.unarchiveCoupon(v.no) : api.archiveCoupon(v.no))
        : v.kind === "campaign"
          ? (v.restore ? api.unarchiveCampaign(v.no) : api.archiveCampaign(v.no))
          : (v.restore ? api.unarchiveSlot(v.no) : api.archiveSlot(v.no)),
    onSuccess: invalidate,
  });

  const couponColumns: Column<Coupon>[] = [
    { header: c.colCouponNo, cell: (x) => x.couponNo, numeric: true, align: "start" },
    { header: c.colName, cell: (x) => x.name },
    { header: c.colType, cell: (x) => <StatusBadge map={couponTypeMap} value={x.type} /> },
    { header: c.colValue, cell: (x) => couponValue(x, c), numeric: true },
    { header: c.colThreshold, cell: (x) => (x.threshold ? money(x.threshold) : c.noThreshold), numeric: true },
    {
      header: c.colBudget,
      width: "14rem",
      // 预算是唯一挡住"发着发着超支"的地方，所以给进度条而不是两个数字
      cell: (c) => (
        <div className="flex items-center gap-2">
          <Progress value={c.issuedAmount} total={c.budget} warnAt={90} showText={false} className="w-20" />
          <span className="tabular-nums text-muted-foreground">
            {money(c.issuedAmount)} / {money(c.budget)}
          </span>
        </div>
      ),
    },
    { header: c.colIssuedRedeemed, cell: (x) => `${x.issued} / ${x.redeemed}`, numeric: true },
    { header: c.colValidRange, cell: (x) => `${fmtTime(x.validFrom)} ~ ${fmtTime(x.validTo)}` },
    { header: c.colStatus, cell: (x) => <CouponStatusBadge value={x.status} /> },
    {
      header: c.colActions,
      cell: (x) => (
        <ArchiveActions
          archived={!!x.archivedAt}
          canWrite={canIssue}
          onArchive={async () => {
            await confirm(archiveConfirm(c.entityCoupon, x.name, x.couponNo, () => archiveMut.mutateAsync({ kind: "coupon", no: x.couponNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(c.entityCoupon, x.name, () => archiveMut.mutateAsync({ kind: "coupon", no: x.couponNo, restore: true })));
          }}
          actions={
            // 只出当前状态允许的那一个动作（合法迁移表见 lib/types/marketing.ts）
            x.status === "DRAFT" ? (
              <Button size="sm" variant="outline" onClick={() => statusMut.mutate({ couponNo: x.couponNo, status: "ACTIVE" })}>{c.btnActivate}</Button>
            ) : x.status === "ACTIVE" ? (
              <Button size="sm" onClick={() => { setIssuing(x); setIssueForm({ target: "ALL", targetDesc: "", count: "100" }); }}>{c.btnIssue}</Button>
            ) : x.status === "PAUSED" ? (
              <Button size="sm" variant="outline" onClick={() => statusMut.mutate({ couponNo: x.couponNo, status: "ACTIVE" })}>{c.btnResume}</Button>
            ) : null
          }
        />
      ),
    },
  ];

  const issueColumns: Column<CouponIssue>[] = [
    { header: c.colIssueNo, cell: (r) => r.issueNo, numeric: true, align: "start" },
    { header: c.colCoupon, cell: (r) => r.couponName },
    { header: c.colTarget, cell: (r) => `${t(`issueTarget.${r.target}`)} · ${r.targetDesc}` },
    { header: c.colCount, cell: (r) => r.count, numeric: true },
    { header: c.colAmount, cell: (r) => money(r.amount), numeric: true },
    // 操作人必须留痕：客服也能发补偿券，出问题要能查到是谁发的
    { header: c.colOperator, cell: (r) => r.operator },
    { header: c.colTime, cell: (r) => fmtTime(r.createdAt) },
  ];

  const campaignColumns: Column<Campaign>[] = [
    { header: c.colCampaignNo, cell: (x) => x.campaignNo, numeric: true, align: "start" },
    { header: c.colName, cell: (x) => x.name },
    { header: c.colType, cell: (x) => <StatusBadge map={campaignTypeMap} value={x.type} /> },
    { header: c.colPosition, cell: (x) => x.position },
    { header: c.colRange, cell: (x) => `${fmtTime(x.startAt)} ~ ${fmtTime(x.endAt)}` },
    { header: c.colSkuCount, cell: (x) => x.skuCount, numeric: true },
    { header: c.colStatus, cell: (x) => <CampaignStatusBadge value={x.status} /> },
    {
      header: c.colActions,
      cell: (x) => (
        <ArchiveActions
          archived={!!x.archivedAt}
          canWrite={canEditCampaign}
          onArchive={async () => {
            await confirm(archiveConfirm(c.entityCampaign, x.name, x.campaignNo, () => archiveMut.mutateAsync({ kind: "campaign", no: x.campaignNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(c.entityCampaign, x.name, () => archiveMut.mutateAsync({ kind: "campaign", no: x.campaignNo, restore: true })));
          }}
        />
      ),
    },
  ];

  const slotColumns: Column<ContentSlot>[] = [
    { header: c.colSlotNo, cell: (s) => s.slotNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (s) => s.title },
    { header: c.colPosition, cell: (s) => <StatusBadge map={slotKindMap} value={s.kind} /> },
    { header: c.colSort, cell: (s) => s.sort, numeric: true },
    // 空 = 全部社区。写"全部社区"而不是留空：留空会被读成"还没配"
    { header: c.colCommunities, cell: (s) => (s.communityNos.length ? s.communityNos.join("、") : c.allCommunities) },
    { header: c.colOnOffline, cell: (s) => `${fmtTime(s.onlineAt)} ~ ${fmtTime(s.offlineAt)}` },
    {
      header: c.colEnabled,
      cell: (s) => (
        <Switch
          checked={s.enabled}
          disabled={!canEditSlot || !!s.archivedAt}
          aria-label={fill(c.ariaEnable, { title: s.title })}
          onChange={(v) => slotEnableMut.mutate({ slotNo: s.slotNo, enabled: v })}
        />
      ),
    },
    {
      header: c.colActions,
      cell: (s) => (
        <ArchiveActions
          archived={!!s.archivedAt}
          canWrite={canEditSlot}
          onArchive={async () => {
            await confirm(archiveConfirm(c.entitySlot, s.title, s.slotNo, () => archiveMut.mutateAsync({ kind: "slot", no: s.slotNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(c.entitySlot, s.title, () => archiveMut.mutateAsync({ kind: "slot", no: s.slotNo, restore: true })));
          }}
        />
      ),
    },
  ];

  const activeList =
    tab === "coupons" ? coupons : tab === "issues" ? issues : tab === "campaigns" ? campaigns : slots;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "coupons" && !canIssue && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="marketing:coupon:issue" note={c.readOnlyNote} className="mb-3" />
      )}
      {tab === "coupons" && (
        <Notice className="mb-3">
          {c.couponNotice}
        </Notice>
      )}
      {tab === "campaigns" && (
        <Notice className="mb-3">
          {c.campaignNotice}
        </Notice>
      )}

      {tab === "member" && (
        <>
          {!canEditMember && <ReadOnlyNotice what={c.memberReadOnlyWhat} perm="marketing:member:update" className="mb-3" />}
          <MemberTab c={c} canEdit={canEditMember} />
        </>
      )}

      {tab !== "member" && (
      <>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={
          tab === "coupons" ? c.searchCoupons
            : tab === "issues" ? c.searchIssues
              : tab === "campaigns" ? c.searchCampaigns
                : c.searchSlots
        }
      >
        {tab === "coupons" && (
          <>
            <FilterSelect aria-label={c.filterCouponType} value={type} onChange={(v) => { setType(v); setPage(1); }} options={couponTypeMap} allLabel={c.filterCouponTypeAll} />
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={couponStatusMap} allLabel={c.filterStatusAll} />
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
          </>
        )}
        {tab === "campaigns" && (
          <>
            <FilterSelect aria-label={c.filterCampaignType} value={type} onChange={(v) => { setType(v); setPage(1); }} options={campaignTypeMap} allLabel={c.filterCampaignTypeAll} />
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={campaignStatusMap} allLabel={c.filterStatusAll} />
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
          </>
        )}
        {tab === "slots" && (
          <>
            <FilterSelect aria-label={c.filterKind} value={kind} onChange={(v) => { setKind(v); setPage(1); }} options={slotKindMap} allLabel={c.filterKindAll} />
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
          </>
        )}
      </Toolbar>

      {tab === "coupons" && (
        <DataTable
          columns={couponColumns} rows={coupons.data?.records} loading={coupons.isLoading}
          error={coupons.error} onRetry={() => coupons.refetch()}
          rowKey={(c) => c.couponNo} rowClassName={archivedRowClass}
          empty={c.emptyCoupons}
        />
      )}
      {tab === "issues" && (
        <DataTable
          columns={issueColumns} rows={issues.data?.records} loading={issues.isLoading}
          error={issues.error} onRetry={() => issues.refetch()}
          rowKey={(r) => r.issueNo}
          empty={c.emptyIssues}
        />
      )}
      {tab === "campaigns" && (
        <DataTable
          columns={campaignColumns} rows={campaigns.data?.records} loading={campaigns.isLoading}
          error={campaigns.error} onRetry={() => campaigns.refetch()}
          rowKey={(c) => c.campaignNo} rowClassName={archivedRowClass}
          empty={c.emptyCampaigns}
        />
      )}
      {tab === "slots" && (
        <DataTable
          columns={slotColumns} rows={slots.data?.records} loading={slots.isLoading}
          error={slots.error} onRetry={() => slots.refetch()}
          rowKey={(s) => s.slotNo} rowClassName={archivedRowClass}
          empty={c.emptySlots}
        />
      )}

      <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />
      </>
      )}

      {/* 发券抽屉：一次发放 = 一条留痕记录 + 一次预算占用 */}
      <Drawer
        open={!!issuing}
        onOpenChange={(o) => !o && setIssuing(null)}
        title={issuing ? fill(c.issueTitle, { name: issuing.name }) : ""}
        desc={issuing?.couponNo}
        footer={
          issuing ? (
            <Button loading={issueMut.isPending} onClick={() => issueMut.mutate()}>{c.btnConfirmIssue}</Button>
          ) : null
        }
      >
        {issuing && (
          <div className="space-y-4">
            <Field className="mb-0" label={c.fieldRemainBudget}>
              <span className="tabular-nums">{money(Math.max(0, issuing.budget - issuing.issuedAmount))}</span>
              <span className="ms-2 text-muted-foreground">{fill(c.perCoupon, { value: couponValue(issuing, c) })}</span>
            </Field>
            <div className="space-y-1">
              <Label htmlFor="iss-target">{c.fieldTarget}</Label>
              <Select
                id="iss-target" className="w-full" value={issueForm.target}
                onChange={(e) => setIssueForm({ ...issueForm, target: e.target.value as IssueTarget })}
              >
                {targetOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="iss-desc">{c.fieldTargetDesc}</Label>
              <Input
                id="iss-desc" className="w-full" value={issueForm.targetDesc}
                placeholder={c.targetDescPlaceholder}
                onChange={(e) => setIssueForm({ ...issueForm, targetDesc: e.target.value })}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="iss-count" required>{c.fieldCount}</Label>
              <Input
                id="iss-count" className="w-full" value={issueForm.count}
                onChange={(e) => setIssueForm({ ...issueForm, count: e.target.value })}
              />
              <p className="txt-caption text-muted-foreground">{c.countHint}</p>
            </div>
          </div>
        )}
      </Drawer>

      {/* 预算编辑：入口在券列表的预算列（点数字），此处只放校验说明 */}
      {budgetEdit && (
        <Drawer open onOpenChange={() => setBudgetEdit(null)} title={c.budgetTitle} desc={budgetEdit.couponNo}>
          <div className="space-y-1">
            <Label htmlFor="bud" required>{c.fieldBudget}</Label>
            <Input id="bud" className="w-full" value={budgetEdit.value} onChange={(e) => setBudgetEdit({ ...budgetEdit, value: e.target.value })} />
            <p className="txt-caption text-muted-foreground">{c.budgetHint}</p>
            <Button className="mt-3" onClick={() => budgetMut.mutate({ couponNo: budgetEdit.couponNo, budget: Math.round(Number(budgetEdit.value) * 100) })}>
              {c.save}
            </Button>
          </div>
        </Drawer>
      )}

      {dialog}
    </div>
  );
}
