"use client";

// 增值包与门店额度（矩阵 P-11.2.2~11.2.6）。
//
// 这一页回答三个问题，每个都对应一个动作：
//   · 谁快掉下去了 → 去催（EXPIRING_7D）
//   · 谁已经掉进宽限期 → 去救（GRACE，**能力全保留**，还来得及）
//   · 谁已经降级 → 去回访（DOWNGRADED，顺便看降级后掉了多少单，那是定价的依据）
//
// 一期**没有在线支付**：商家点「升级」→ 联系平台 → 运营在这里授予。
// 所以这一页是「收款链路」的替代品，不是它的前端 —— 页面上必须把这句话写出来，
// 否则运营会去找那个不存在的「收款记录」。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { usePaging } from "@/lib/use-paging";
import type { MerchantPlanRow, PlanDef, PlanStatus } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { MerchantsCopy as Copy } from "./copy";

/**
 * 三档状态**不能压成「有效 / 无效」两档**：GRACE 的商家能力全在，
 * 而运营对他要做的事（打电话）与对 EXPIRED 的（回访、谈重新订阅）完全不同。
 */
const useStatusMap = (c: Copy): StatusMap<PlanStatus> => ({
  ACTIVE: { label: c.plStatusActive, tone: "success" },
  GRACE: { label: c.plStatusGrace, tone: "warning" },
  EXPIRED: { label: c.plStatusExpired, tone: "danger" },
});

const FILTER_OPTIONS = (c: Copy) => [
  { value: "EXPIRING_7D", label: c.plFilterExpiring },
  { value: "GRACE", label: c.plFilterGrace },
  { value: "DOWNGRADED", label: c.plFilterDowngraded },
];

/** 到期日按天显示：这张表上的决策粒度是「今天要不要打电话」，不是几点几分。 */
const day = (ms?: number | null) => (ms == null ? null : new Date(ms).toLocaleDateString());

export function PlansTab({ c }: { c: Copy }) {
  const qc = useQueryClient();
  const allow = useCan();
  const statusMap = useStatusMap(c);

  const [keyword, setKeyword] = useState("");
  const [filter, setFilter] = useState("");
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<MerchantPlanRow | null>(null);

  // 授予与额度覆盖同一个码（处置面）；改档位定义是另一个码 ——
  // BD 能给某家授予套餐，但不能改「套餐是什么」
  const canGrant = allow("merchant:merchant:ban");
  const canEditDef = allow("system:param:update");

  const q = { keyword, filter, page, size };
  const list = useQuery({ queryKey: ["merchant-plans", q], queryFn: () => api.merchantPlans(q) });
  const defs = useQuery({ queryKey: ["plan-defs"], queryFn: () => api.planDefs() });
  const signals = useQuery({ queryKey: ["plan-upgrade-signals"], queryFn: () => api.planUpgradeSignals() });

  const columns: Column<MerchantPlanRow>[] = [
    { header: c.plColMerchant, cell: (r) => `${r.merchantName}（${r.merchantNo}）` },
    {
      header: c.plColPlan,
      cell: (r) => (
        <span className="flex items-center gap-2">
          {r.planCode}
          {/* 额度来源要显示得出来 —— 否则「这家怎么是 5 家？」只能靠翻审计日志回答 */}
          {r.quotaSource === "OVERRIDE" && <Badge tone="warning">{c.plSourceOverride}</Badge>}
          {r.quotaSource === "CONFIG" && <Badge tone="muted">{c.plSourceConfig}</Badge>}
        </span>
      ),
    },
    {
      header: c.plColQuota,
      // 用满了要看得见：它同时是「他为什么打电话来」和「该不该推升档」的答案
      cell: (r) => (
        <span className="flex items-center gap-2">
          {r.storeUsed}/{r.storeQuota}
          {r.storeUsed >= r.storeQuota && <Badge tone="warning">{c.plQuotaFull}</Badge>}
        </span>
      ),
      numeric: true, align: "start",
    },
    { header: c.plColStaff, cell: (r) => `${r.staffUsed}/${r.staffQuota}`, numeric: true, align: "start" },
    { header: c.plColStatus, cell: (r) => <StatusBadge map={statusMap} value={r.status} /> },
    {
      header: c.plColExpire,
      cell: (r) => day(r.expireAt) ?? <span className="text-muted-foreground">{c.plExpireNone}</span>,
    },
    {
      header: c.plColActions,
      cell: (r) => <Button size="sm" variant="outline" onClick={() => setCurrent(r)}>{c.plDetail}</Button>,
    },
  ];

  return (
    <>
      <Notice className="mb-3">{c.plNotice}</Notice>

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.plSearchPh}>
        <FilterSelect
          aria-label={c.plColStatus} value={filter} allLabel={c.plFilterAll}
          options={FILTER_OPTIONS(c)} onChange={(v) => { setFilter(v); setPage(1); }}
        />
      </Toolbar>

      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.merchantNo}
        empty={c.plEmpty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <PlanDefsBlock c={c} defs={defs.data} loading={defs.isLoading} canEdit={canEditDef} />

      <UpgradeSignalsBlock c={c} rows={signals.data} loading={signals.isLoading} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.merchantName ?? ""}
        desc={current?.merchantNo}
        width="w-[560px]"
      >
        {current && (
          <PlanDrawer
            c={c} row={current} defs={defs.data ?? []}
            canGrant={canGrant}
            onSaved={(r) => {
              setCurrent(r);
              qc.invalidateQueries({ queryKey: ["merchant-plans"] });
              // 授予会恢复被降级压下的门店 —— 门店档案那张表跟着变
              qc.invalidateQueries({ queryKey: ["stores-govern"] });
              // 在用数变了，档位定义那一块的 subscriberCount 也要重取
              qc.invalidateQueries({ queryKey: ["plan-defs"] });
            }}
          />
        )}
      </Drawer>
    </>
  );
}

/** 抽屉：订阅现状 + 授予/延长 + 额度覆盖。 */
function PlanDrawer({ c, row, defs, canGrant, onSaved }: {
  c: Copy;
  row: MerchantPlanRow;
  defs: PlanDef[];
  canGrant: boolean;
  onSaved: (r: MerchantPlanRow) => void;
}) {
  const statusMap = useStatusMap(c);
  const [planCode, setPlanCode] = useState(row.planCode);
  const [months, setMonths] = useState("");
  const [reason, setReason] = useState("");
  const [storeQuota, setStoreQuota] = useState(String(row.storeQuota));
  const [staffQuota, setStaffQuota] = useState(String(row.staffQuota));
  const [quotaReason, setQuotaReason] = useState("");

  // 换行（点了列表里另一家）时把表单归位 —— 否则上一家的理由会留在输入框里，
  // 而它下一秒就会被写进另一家的审计
  useEffect(() => {
    setPlanCode(row.planCode);
    setMonths("");
    setReason("");
    setStoreQuota(String(row.storeQuota));
    setStaffQuota(String(row.staffQuota));
    setQuotaReason("");
  }, [row.merchantNo, row.planCode, row.storeQuota, row.staffQuota]);

  const grant = useMutation({
    mutationFn: () => api.grantPlan({
      merchantNo: row.merchantNo, planCode,
      // 空 = 只补缴不延长（后端据此决定要不要刷新额度快照）
      months: months.trim() ? Number(months) : undefined,
      reason,
    }),
    onSuccess: (r) => { onSaved(r); notify.success(c.plToastGranted); },
  });

  const override = useMutation({
    mutationFn: () => api.overridePlanQuota({
      merchantNo: row.merchantNo,
      // 清空 = 回到档位快照，**不是设成 0** —— 后者会让这家一家店都开不了
      storeQuota: storeQuota.trim() ? Number(storeQuota) : null,
      staffQuota: staffQuota.trim() ? Number(staffQuota) : null,
      reason: quotaReason,
    }),
    onSuccess: (r) => { onSaved(r); notify.success(c.plToastQuota); },
  });

  return (
    <div>
      <DrawerSection first title={c.plSecPlan}>
        <FieldGrid>
          <Field className="mb-3" label={c.plColPlan}>{row.planCode}</Field>
          <Field className="mb-3" label={c.plColStatus}><StatusBadge map={statusMap} value={row.status} /></Field>
          <Field className="mb-3" label={c.plColQuota}>{row.storeUsed}/{row.storeQuota}</Field>
          <Field className="mb-3" label={c.plColStaff}>{row.staffUsed}/{row.staffQuota}</Field>
          <Field className="mb-3" label={c.plColExpire}>{day(row.expireAt) ?? c.plExpireNone}</Field>
          <Field className="mb-3" label={c.plFieldGrantedBy}>{row.grantedBy ?? "-"}</Field>
          <Field className="mb-3" label={c.plFieldQuotaSource}>
            {row.quotaSource === "OVERRIDE" ? c.plSourceOverride
              : row.quotaSource === "CONFIG" ? c.plSourceConfig : c.plSourcePlan}
          </Field>
          <Field className="mb-3" label={c.plFieldTrialUsed}>{row.trialUsed ? "✓" : "-"}</Field>
        </FieldGrid>
        {/* 降级时间非空 = 已经压过店了。它是「这家为什么只剩一家店在营业」的答案 */}
        {row.downgradedAt != null && (
          <Field label={c.plDowngradedAt}>{day(row.downgradedAt)}</Field>
        )}
      </DrawerSection>

      <DrawerSection title={c.plSecGrant} desc={c.plGrantMonthsHint}>
        {canGrant ? (
          <div className="space-y-3">
            <FilterSelect
              aria-label={c.plGrantPlan} value={planCode} allLabel={c.plGrantPlan}
              options={defs.filter((d) => d.enabled).map((d) => ({ value: d.planCode, label: `${d.name}（${d.storeQuota} 店）` }))}
              onChange={setPlanCode}
            />
            <Input
              type="number" min={1} placeholder={c.plGrantMonths}
              value={months} onChange={(e) => setMonths(e.target.value)}
            />
            <Input placeholder={c.plGrantReasonPh} value={reason} onChange={(e) => setReason(e.target.value)} />
            <Button
              loading={grant.isPending}
              disabled={!reason.trim() || !planCode}
              onClick={() => grant.mutate()}
            >
              {c.plBtnGrant}
            </Button>
          </div>
        ) : <ReadOnlyNotice what={c.plReadOnlyGrant} perm="merchant:merchant:ban" />}
      </DrawerSection>

      <DrawerSection title={c.plSecQuota} desc={c.plQuotaHint}>
        {canGrant ? (
          <div className="space-y-3">
            <Input
              type="number" min={0} placeholder={c.plQuotaStore}
              value={storeQuota} onChange={(e) => setStoreQuota(e.target.value)}
            />
            <Input
              type="number" min={0} placeholder={c.plQuotaStaff}
              value={staffQuota} onChange={(e) => setStaffQuota(e.target.value)}
            />
            <Input placeholder={c.plGrantReasonPh} value={quotaReason} onChange={(e) => setQuotaReason(e.target.value)} />
            <Button
              variant="outline" loading={override.isPending}
              disabled={!quotaReason.trim()}
              onClick={() => override.mutate()}
            >
              {c.plBtnQuota}
            </Button>
          </div>
        ) : <ReadOnlyNotice what={c.plReadOnlyGrant} perm="merchant:merchant:ban" />}
      </DrawerSection>
    </div>
  );
}

/**
 * 档位定义。**页内区块而不是独立菜单项** —— 它的权限码是 `system:*`，
 * 而叶子的 perm 前缀必须等于 section 的 module（nav.test.ts 锁着）。
 */
function PlanDefsBlock({ c, defs, loading, canEdit }: {
  c: Copy; defs?: PlanDef[]; loading: boolean; canEdit: boolean;
}) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<PlanDef | null>(null);

  const columns: Column<PlanDef>[] = [
    { header: c.plDefCol, cell: (d) => `${d.name}（${d.planCode}）` },
    { header: c.plDefColStore, cell: (d) => d.storeQuota, numeric: true, align: "start" },
    { header: c.plDefColStaff, cell: (d) => d.staffQuota, numeric: true, align: "start" },
    { header: c.plDefColCross, cell: (d) => (d.crossStoreStats ? "✓" : "-") },
    { header: c.plDefColTrial, cell: (d) => d.trialDays, numeric: true, align: "start" },
    // 「在用」是「只影响之后新订阅的人」那句话的具体量。
    // 不显示这个数，改档位的人只能凭感觉判断影响面
    { header: c.plDefColSubs, cell: (d) => d.subscriberCount, numeric: true, align: "start" },
    {
      header: c.plDefColEnabled,
      cell: (d) => (d.enabled ? <Badge tone="success">{c.plDefEnabled}</Badge> : <Badge tone="muted">{c.plDefRetired}</Badge>),
    },
    {
      header: c.plColActions,
      cell: (d) => (canEdit
        ? <Button size="sm" variant="outline" onClick={() => setEditing(d)}>{c.plDefEdit}</Button>
        : <span className="text-muted-foreground">-</span>),
    },
  ];

  return (
    <section className="mt-6">
      <h3 className="txt-h3 mb-1">{c.plSecDefs}</h3>
      <p className="txt-caption text-muted-foreground mb-2">{c.plDefsHint}</p>
      <DataTable columns={columns} rows={defs} loading={loading} rowKey={(d) => d.planCode} />
      {!canEdit && <ReadOnlyNotice what={c.plReadOnlyDef} perm="system:param:update" />}

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing ? `${editing.name}（${editing.planCode}）` : ""}
        desc={c.plDefsHint}
      >
        {editing && (
          <PlanDefForm
            c={c} def={editing}
            onSaved={() => {
              setEditing(null);
              qc.invalidateQueries({ queryKey: ["plan-defs"] });
              // 已订阅的额度**不会**跟着变，所以这里刻意不刷 merchant-plans ——
              // 刷了看不出变化，反而会让人以为是没生效
              notify.success(c.plToastDefSaved);
            }}
          />
        )}
      </Drawer>
    </section>
  );
}

function PlanDefForm({ c, def, onSaved }: { c: Copy; def: PlanDef; onSaved: () => void }) {
  const { confirm, dialog } = useConfirm();
  const [storeQuota, setStoreQuota] = useState(String(def.storeQuota));
  const [staffQuota, setStaffQuota] = useState(String(def.staffQuota));
  const [crossStoreStats, setCross] = useState(def.crossStoreStats);
  const [trialDays, setTrialDays] = useState(String(def.trialDays));
  const [enabled, setEnabled] = useState(def.enabled);

  const save = useMutation({
    mutationFn: () => api.savePlanDef({
      planCode: def.planCode,
      storeQuota: Number(storeQuota), staffQuota: Number(staffQuota),
      crossStoreStats, trialDays: Number(trialDays), enabled,
    }),
    onSuccess: onSaved,
  });

  /*
   * 二次确认，且**把「有几家在用」和「只影响新订阅」两句话放进弹窗里** ——
   * 改这一行影响的是这一档之后的所有新订阅，而界面上它长得和改一个普通表单字段一样。
   * 这里也是唯一能把「已订阅的人不受影响」讲清楚的时机：
   * 不讲的话，运营改完会以为存量商家的额度也跟着变了，转头去给他们打电话。
   */
  const askThenSave = () => confirm({
    title: fill(c.plDefConfirmTitle, { name: def.name }),
    desc: fill(c.plDefConfirmDesc, { n: def.subscriberCount }),
    action: () => save.mutateAsync(),
  });

  return (
    <DrawerSection first title={c.plSecDefs}>
      <div className="space-y-3">
        <Field label={c.plDefColStore}>
          <Input type="number" min={1} value={storeQuota} onChange={(e) => setStoreQuota(e.target.value)} />
        </Field>
        <Field label={c.plDefColStaff}>
          <Input type="number" min={1} value={staffQuota} onChange={(e) => setStaffQuota(e.target.value)} />
        </Field>
        <Field label={c.plDefColTrial}>
          <Input type="number" min={0} value={trialDays} onChange={(e) => setTrialDays(e.target.value)} />
        </Field>
        <label className="flex items-center gap-2 txt-body">
          <input type="checkbox" checked={crossStoreStats} onChange={(e) => setCross(e.target.checked)} />
          {c.plDefColCross}
        </label>
        {/* 停售只挡新授，**已订阅的照常用到到期** —— 那才是这个开关的语义 */}
        <label className="flex items-center gap-2 txt-body">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          {c.plDefColEnabled}
        </label>
        <Button loading={save.isPending} disabled={Number(storeQuota) < 1} onClick={askThenSave}>
          {c.plBtnDefSave}
        </Button>
      </div>
      {dialog}
    </DrawerSection>
  );
}

/** 升档信号：一个人名下多个主体 —— 他已经在多店经营，只是绕过了额度。 */
function UpgradeSignalsBlock({ c, rows, loading }: {
  c: Copy; rows?: { ownerUserNo: string; entityNos: string[]; entityNames: string[]; entityCount: number }[]; loading: boolean;
}) {
  const columns: Column<NonNullable<typeof rows>[number]>[] = [
    { header: c.plSignalOwner, cell: (r) => r.ownerUserNo },
    { header: c.plSignalEntities, cell: (r) => r.entityNames.join("、") },
    { header: c.plDefColSubs, cell: (r) => r.entityCount, numeric: true, align: "start" },
  ];
  return (
    <section className="mt-6">
      <h3 className="txt-h3 mb-1">{c.plSecSignals}</h3>
      <p className="txt-caption text-muted-foreground mb-2">{c.plSignalsHint}</p>
      <DataTable columns={columns} rows={rows} loading={loading} rowKey={(r) => r.ownerUserNo} empty={c.plSignalsEmpty} />
    </section>
  );
}
