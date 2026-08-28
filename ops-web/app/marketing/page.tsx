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
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { CouponBuildableType, MerchantCampaign, ContentSlot, Coupon, CouponIssue, CouponStatus, IssueTarget } from "@/lib/types";
import {
  PlatformSlotStatusBadge, CouponStatusBadge, usePlatformSlotStatusMap, usePlatformSlotTypeMap,
  useCouponStatusMap, useCouponTypeMap, useSlotKindMap,
  useMerchantCampaignTypeMap, useMerchantCampaignStatusMap,
} from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 会员卡自成一块 —— 与券/活动/内容位三个 tab 只共用文案表
import { MemberTab } from "./member-tab";
import { ExposureTab } from "./exposure-tab";
import { ArchiveActions, ShowArchivedToggle, ARCHIVE_LABEL_KEY, UNARCHIVE_LABEL_KEY, archiveConfirm, archivedRowClass, unarchiveConfirm } from "@/components/archive";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { RowActions } from "@/components/ui/dropdown-menu";
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
/*
 * 末尾两个是**敞口**（P8 · O5–O6）：看的是「谁家的券会失控」，
 * 不是券本身。放在营销页而不是会员页 —— 权限码是 marketing:*，
 * 挂到会员下面会让看会员的人顺带拿到营销的入口。
 */
const TAB_KEYS = ["coupons", "issues", "campaigns", "slots", "member",
                  "promoCoupons", "promoActivities"] as const;

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

/** 建券表单。字段都是字符串——数值转换与校验留到提交那一刻，与 MemberTab 的 Form 同一套做法。 */
interface CouponForm {
  couponNo?: string;
  name: string;
  type: CouponBuildableType;
  faceMinor: string;
  discountRate: string;
  maxDiscountMinor: string;
  threshold: string;
  totalCount: string;
  perUserLimit: string;
  budget: string;
  validFrom: string;
  validTo: string;
}

const EMPTY_COUPON_FORM: CouponForm = {
  name: "", type: "FULL_CUT", faceMinor: "", discountRate: "", maxDiscountMinor: "",
  threshold: "", totalCount: "", perUserLimit: "1", budget: "", validFrom: "", validTo: "",
};

const toCouponForm = (x: Coupon): CouponForm => ({
  couponNo: x.couponNo, name: x.name, type: x.type === "DISCOUNT" ? "DISCOUNT" : "FULL_CUT",
  faceMinor: x.type === "DISCOUNT" ? "" : String(x.value / 100),
  discountRate: x.type === "DISCOUNT" ? (x.value / 1000).toFixed(1) : "",
  maxDiscountMinor: x.type === "DISCOUNT" ? String(x.maxDiscountMinor / 100) : "",
  threshold: x.threshold ? String(x.threshold / 100) : "",
  totalCount: String(x.totalCount), perUserLimit: String(x.perUserLimit),
  budget: x.budget ? String(x.budget / 100) : "",
  validFrom: toDatetimeLocal(x.validFrom), validTo: toDatetimeLocal(x.validTo),
});

const toDatetimeLocal = (ms: number) => {
  const d = new Date(ms - new Date().getTimezoneOffset() * 60_000);
  return d.toISOString().slice(0, 16);
};

export default function MarketingPage() {
  return <Suspense fallback={null}><MarketingInner /></Suspense>;
}

function MarketingInner() {
  const c = useCopy(MARKETING_COPY);
  const tabs = useNavTabs("/marketing", TAB_KEYS);
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
  const [issueForm, setIssueForm] = useState<{ target: IssueTarget; targetDesc: string; userNo: string; count: string }>({
    // 默认「指定用户」：它是唯一后端能真发的，也是最高频的场景（客服补偿券）
    target: "SINGLE_USER", targetDesc: "", userNo: "", count: "1",
  });
  const [budgetEdit, setBudgetEdit] = useState<{ couponNo: string; value: string } | null>(null);
  const [couponForm, setCouponForm] = useState<CouponForm | null>(null);

  const canIssue = allow("marketing:coupon:issue");
  const canEditCampaign = allow("marketing:campaign:update");
  const canEditSlot = allow("marketing:slot:update");
  const canEditMember = allow("marketing:member:update");

  const couponTypeMap = useCouponTypeMap();
  const couponStatusMap = useCouponStatusMap();
  // 商家活动，不是平台场次 —— 两套枚举不能混用
  const campaignTypeMap = useMerchantCampaignTypeMap();
  const campaignStatusMap = useMerchantCampaignStatusMap();
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
    // 理由必填：后端把它写进审计，空理由 10400。此前这里不传 reason，
    // 于是**运营在真后端下点「暂停」必然失败** —— mock 没有这条校验所以演示一切正常
    mutationFn: (v: { couponNo: string; status: CouponStatus; reason: string }) =>
      api.setCouponStatus(v.couponNo, v.status, v.reason),
    onSuccess: () => { invalidate(); notify.success(c.toastCouponStatus); },
  });
  const campaignToggleMut = useMutation({
    mutationFn: (v: { campaignNo: string; running: boolean; reason: string }) =>
      api.toggleCampaign(v.campaignNo, v.running, v.reason),
    onSuccess: () => { invalidate(); notify.success(c.toastCampaignToggled); },
  });
  const budgetMut = useMutation({
    mutationFn: (v: { couponNo: string; budget: number }) => api.setCouponBudget(v.couponNo, v.budget),
    onSuccess: () => { invalidate(); setBudgetEdit(null); notify.success(c.toastBudgetSaved); },
  });
  const saveCouponMut = useMutation({
    mutationFn: (f: CouponForm) =>
      api.saveCoupon({
        couponNo: f.couponNo, name: f.name, type: f.type,
        faceMinor: f.type === "FULL_CUT" ? Math.round(Number(f.faceMinor) * 100) : undefined,
        // 折扣输入的是"折"（8.5 = 八五折），万分比 = 折 × 1000
        discountRate: f.type === "DISCOUNT" ? Math.round(Number(f.discountRate) * 1000) : undefined,
        maxDiscountMinor: f.type === "DISCOUNT" ? Math.round(Number(f.maxDiscountMinor) * 100) : undefined,
        threshold: f.threshold ? Math.round(Number(f.threshold) * 100) : 0,
        totalCount: Math.round(Number(f.totalCount)),
        perUserLimit: f.perUserLimit ? Math.round(Number(f.perUserLimit)) : 1,
        budget: f.budget ? Math.round(Number(f.budget) * 100) : 0,
        validFrom: new Date(f.validFrom).getTime(),
        validTo: new Date(f.validTo).getTime(),
      }),
    onSuccess: () => { invalidate(); setCouponForm(null); notify.success(c.toastCouponSaved); },
  });
  const issueMut = useMutation({
    mutationFn: () =>
      api.issueCoupon({
        couponNo: issuing!.couponNo,
        target: issueForm.target,
        targetDesc: issueForm.targetDesc || targetOptions.find((o) => o.value === issueForm.target)!.label,
        userNo: issueForm.userNo.trim() || undefined,
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

  /**
   * 停 / 启一张券。**走确认框收理由** —— 后端要求它，且它会写进审计给商家看。
   * 不做成「点了就改」：这是改别人家的券，一次误点在领券中心是立刻可见的。
   */
  const askCouponStatus = (x: Coupon, status: CouponStatus) =>
    confirm({
      title: status === "PAUSED" ? c.pauseCouponTitle : c.resumeCouponTitle,
      desc: `${x.name}（${x.couponNo}）`,
      danger: status === "PAUSED",
      requireReason: true,
      action: (reason) => statusMut.mutateAsync({ couponNo: x.couponNo, status, reason }),
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
      // 预算是唯一挡住"发着发着超支"的地方，所以给进度条而不是两个数字。
      // budget=0 是「不限」而不是「零元」—— 画成 ¥5.00 / ¥0.00 会读成已经超支，
      // 而它恰恰是「这张券没人在管支出」，两个意思相反
      // 整格可点 = 改预算的入口。此前抽屉、输入框、保存按钮、mutation 四样都在，
      // **唯独没有任何地方打开那个抽屉** —— 加上后端那条缺失的端点，
      // 这条链上五环齐全就差最后一个按钮，而预算因此永远是 0
      cell: (r) => (
        <button
          type="button"
          disabled={!canIssue}
          title={c.budgetTitle}
          className="focus-ring flex w-full items-center gap-2 rounded-field px-1 transition-colors hover:bg-accent disabled:cursor-default disabled:hover:bg-transparent"
          onClick={() => setBudgetEdit({ couponNo: r.couponNo, value: (r.budget / 100).toFixed(2) })}
        >
          {r.budget > 0 && (
            <Progress value={r.issuedAmount} total={r.budget} warnAt={90} showText={false} className="w-20" />
          )}
          <span className="tabular-nums text-muted-foreground">
            {money(r.issuedAmount)} / {r.budget > 0 ? money(r.budget) : c.budgetUnlimited}
          </span>
        </button>
      ),
    },
    { header: c.colIssuedRedeemed, cell: (x) => `${x.issued} / ${x.redeemed}`, numeric: true },
    { header: c.colValidRange, cell: (x) => `${fmtTime(x.validFrom)} ~ ${fmtTime(x.validTo)}` },
    { header: c.colStatus, cell: (x) => <CouponStatusBadge value={x.status} /> },
    {
      header: c.colActions,
      cell: (x) => {
        // 行内只留「这个状态下最常按的那一个」（DRAFT→启用 / ACTIVE→发券 / PAUSED→恢复），
        // 其余（编辑、暂停、归档）收进「更多」——行内动作 ≤2 个是本站表格操作列的约定
        // （见 components/README.md），这一列此前平铺到 4 个按钮，表格被撑得要横向滚动。
        const primary =
          x.status === "DRAFT" ? (
            <Button size="sm" variant="outline" onClick={() => askCouponStatus(x, "ACTIVE")}>{c.btnActivate}</Button>
          ) : x.status === "ACTIVE" ? (
            <Button size="sm" onClick={() => { setIssuing(x); setIssueForm({ target: "SINGLE_USER", targetDesc: "", userNo: "", count: "1" }); }}>{c.btnIssue}</Button>
          ) : x.status === "PAUSED" ? (
            <Button size="sm" variant="outline" onClick={() => askCouponStatus(x, "ACTIVE")}>{c.btnResume}</Button>
          ) : null;
        return (
          <div className="flex flex-nowrap items-center gap-2">
            {primary}
            <RowActions
              actions={[
                canIssue && !x.archivedAt && (x.type === "FULL_CUT" || x.type === "DISCOUNT") && {
                  label: c.actionEditCoupon, onSelect: () => setCouponForm(toCouponForm(x)),
                },
                // 出事时的止损手段：券从领券中心消失、领取被拒，已领到手的不动
                canIssue && !x.archivedAt && x.status === "ACTIVE" && {
                  label: c.btnPause, onSelect: () => askCouponStatus(x, "PAUSED"), danger: true,
                },
                canIssue && (x.archivedAt
                  ? {
                      label: t(UNARCHIVE_LABEL_KEY),
                      onSelect: () => confirm(unarchiveConfirm(c.entityCoupon, x.name, () => archiveMut.mutateAsync({ kind: "coupon", no: x.couponNo, restore: true }))),
                    }
                  : {
                      label: t(ARCHIVE_LABEL_KEY), danger: true,
                      onSelect: () => confirm(archiveConfirm(c.entityCoupon, x.name, x.couponNo, () => archiveMut.mutateAsync({ kind: "coupon", no: x.couponNo, restore: false }))),
                    }),
              ]}
            />
          </div>
        );
      },
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

  /*
   * 这一列表是**商家自建的店铺活动**，不是平台投放场次（后端还没有那个对象）。
   * 于是没有「位置」列 —— 那是场次专属的；商品数取 goodsNos 的长度，
   * 后端 CampaignVO 里一直有这个字段，此前因为类型对不上而恒为空。
   */
  /** 停 / 启商家活动。与停券同一套：理由必填，写进审计 */
  const askCampaignToggle = (x: MerchantCampaign, running: boolean) =>
    confirm({
      title: running ? c.resumeCampaignTitle : c.pauseCampaignTitle,
      desc: `${x.name}（${x.merchantNo}）`,
      danger: !running,
      requireReason: true,
      action: (reason) => campaignToggleMut.mutateAsync({ campaignNo: x.campaignNo, running, reason }),
    });

  const campaignColumns: Column<MerchantCampaign>[] = [
    { header: c.colCampaignNo, cell: (x) => x.campaignNo, numeric: true, align: "start" },
    { header: c.colName, cell: (x) => x.name },
    { header: c.colMerchant, cell: (x) => x.merchantNo },
    { header: c.colType, cell: (x) => <StatusBadge map={campaignTypeMap} value={x.type} /> },
    { header: c.colRange, cell: (x) => `${fmtTime(x.startAt)} ~ ${fmtTime(x.endAt)}` },
    { header: c.colSkuCount, cell: (x) => x.goodsNos?.length ?? 0, numeric: true },
    { header: c.colStatus, cell: (x) => <StatusBadge map={campaignStatusMap} value={x.status} /> },
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
          actions={
            /*
             * 平台对商家活动的**全部**能力就是这一个开关：看得见、能停。
             * 不能建也不能改内容 —— 那是商家自己的经营决定。
             * ENDED 的不给按钮：已经结束的活动没有「停」这回事。
             */
            x.status === "RUNNING" ? (
              <Button size="sm" variant="outline" onClick={() => askCampaignToggle(x, false)}>{c.btnPause}</Button>
            ) : x.status === "PAUSED" ? (
              <Button size="sm" variant="outline" onClick={() => askCampaignToggle(x, true)}>{c.btnResume}</Button>
            ) : null
          }
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

      {(tab === "promoCoupons" || tab === "promoActivities") && (
        <ExposureTab
          c={c}
          kind={tab === "promoCoupons" ? "coupons" : "activities"}
          canStop={allow("marketing:campaign:update")}
        />
      )}

      {tab !== "member" && tab !== "promoCoupons" && tab !== "promoActivities" && (
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
        onAdd={tab === "coupons" ? () => setCouponForm(EMPTY_COUPON_FORM) : undefined}
        addLabel={c.actionNewCoupon}
        canAdd={canIssue}
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
            <Button
              loading={issueMut.isPending}
              disabled={issueForm.target === "SINGLE_USER" && !issueForm.userNo.trim()}
              onClick={() => issueMut.mutate()}
            >{c.btnConfirmIssue}</Button>
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
            {/*
              收券人。只在 SINGLE_USER 时出现，因为只有这一种后端能真发 ——
              其余三种的收件人在入口处就不存在（「定向说明」是自由文本，
              给不出社区号也给不出 userNo），后端会返回 10501「还没做完」。
              不做成隐藏后仍可提交：那等于让运营点一次才知道这条路不通。
            */}
            {issueForm.target === "SINGLE_USER" && (
              <div className="space-y-1">
                <Label htmlFor="iss-user" required>{c.fieldUserNo}</Label>
                <Input
                  id="iss-user" className="w-full" value={issueForm.userNo}
                  placeholder={c.userNoPlaceholder}
                  onChange={(e) => setIssueForm({ ...issueForm, userNo: e.target.value })}
                />
                <p className="txt-caption text-muted-foreground">{c.userNoHint}</p>
              </div>
            )}
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

      {/* 建券 / 改券抽屉（TDD-营销预算前置）。字段随类型切换显隐——折扣券封顶必填，
          满减券根本不问封顶，避免运营看着一个跟当前类型无关的必填框发懵。 */}
      {couponForm && (
        <Drawer
          open
          onOpenChange={(o) => !o && setCouponForm(null)}
          title={couponForm.couponNo ? fill(c.editCouponTitle, { name: couponForm.name }) : c.newCouponTitle}
          desc={couponForm.couponNo}
          footer={
            <Button loading={saveCouponMut.isPending} onClick={() => saveCouponMut.mutate(couponForm)}>
              {c.btnSaveCoupon}
            </Button>
          }
        >
          <div className="space-y-4">
            {(() => {
              const issued = coupons.data?.records.find((r) => r.couponNo === couponForm.couponNo)?.issued ?? 0;
              return issued > 0 ? <Notice tone="warning">{fill(c.issuedLockedNotice, { n: issued })}</Notice> : null;
            })()}
            <div className="space-y-1">
              <Label htmlFor="cp-name" required>{c.fieldCouponName}</Label>
              <Input id="cp-name" className="w-full" value={couponForm.name}
                onChange={(e) => setCouponForm({ ...couponForm, name: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="cp-type" required>{c.fieldCouponType}</Label>
              <Select id="cp-type" className="w-full" value={couponForm.type}
                onChange={(e) => setCouponForm({ ...couponForm, type: e.target.value as CouponForm["type"] })}>
                <option value="FULL_CUT">{c.ctFullCut}</option>
                <option value="DISCOUNT">{c.ctDiscount}</option>
              </Select>
            </div>
            <FieldGrid>
              {couponForm.type === "FULL_CUT" ? (
                <div className="space-y-1">
                  <Label htmlFor="cp-face" required>{c.fieldFace}</Label>
                  <Input id="cp-face" className="w-full" value={couponForm.faceMinor}
                    onChange={(e) => setCouponForm({ ...couponForm, faceMinor: e.target.value })} />
                </div>
              ) : (
                <>
                  <div className="space-y-1">
                    <Label htmlFor="cp-rate" required>{c.fieldDiscountRate}</Label>
                    <Input id="cp-rate" className="w-full" value={couponForm.discountRate}
                      onChange={(e) => setCouponForm({ ...couponForm, discountRate: e.target.value })} />
                    <p className="txt-caption text-muted-foreground">{c.discountRateHint}</p>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="cp-cap" required>{c.fieldMaxDiscount}</Label>
                    <Input id="cp-cap" className="w-full" value={couponForm.maxDiscountMinor}
                      onChange={(e) => setCouponForm({ ...couponForm, maxDiscountMinor: e.target.value })} />
                    <p className="txt-caption text-muted-foreground">{c.maxDiscountHint}</p>
                  </div>
                </>
              )}
              <div className="space-y-1">
                <Label htmlFor="cp-threshold">{c.fieldThreshold}</Label>
                <Input id="cp-threshold" className="w-full" value={couponForm.threshold}
                  onChange={(e) => setCouponForm({ ...couponForm, threshold: e.target.value })} />
                <p className="txt-caption text-muted-foreground">{c.thresholdHint}</p>
              </div>
            </FieldGrid>
            <FieldGrid>
              <div className="space-y-1">
                <Label htmlFor="cp-total" required>{c.fieldTotalCount}</Label>
                <Input id="cp-total" className="w-full" value={couponForm.totalCount}
                  onChange={(e) => setCouponForm({ ...couponForm, totalCount: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="cp-per-user">{c.fieldPerUserLimit}</Label>
                <Input id="cp-per-user" className="w-full" value={couponForm.perUserLimit}
                  onChange={(e) => setCouponForm({ ...couponForm, perUserLimit: e.target.value })} />
              </div>
            </FieldGrid>
            <div className="space-y-1">
              <Label htmlFor="cp-budget">{c.fieldCouponBudget}</Label>
              <Input id="cp-budget" className="w-full" value={couponForm.budget}
                onChange={(e) => setCouponForm({ ...couponForm, budget: e.target.value })} />
              <p className="txt-caption text-muted-foreground">{c.couponBudgetHint}</p>
            </div>
            <FieldGrid>
              <div className="space-y-1">
                <Label htmlFor="cp-from" required>{c.fieldValidFrom}</Label>
                <Input id="cp-from" type="datetime-local" className="w-full" value={couponForm.validFrom}
                  onChange={(e) => setCouponForm({ ...couponForm, validFrom: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="cp-to" required>{c.fieldValidTo}</Label>
                <Input id="cp-to" type="datetime-local" className="w-full" value={couponForm.validTo}
                  onChange={(e) => setCouponForm({ ...couponForm, validTo: e.target.value })} />
              </div>
            </FieldGrid>
          </div>
        </Drawer>
      )}

      {dialog}
    </div>
  );
}
