"use client";

// 增长与归因（矩阵 P-9）。归因链路同时是风控识别异常裂变的数据源 ——
// 「同设备批量注册后互相邀请」正是从这条链路上看出来的（见 /risk）。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { GROWTH_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import {
  ATTR_SOURCES, ATTR_WINDOW_MAX, ATTR_WINDOW_MIN,
  type AttrSource, type AttributionTrace, type ConflictPolicy, type FissionCampaign, type NewUserFactor,
} from "@/lib/types";
import { useAttrSourceMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Radio, RadioGroup } from "@/components/ui/radio-group";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof GROWTH_COPY)["zh"];
const TAB_KEYS = ["rule", "traces", "fission"] as const;

const SOURCE_LABEL = (c: Copy): Record<AttrSource, string> => ({
  STORE_CODE: c.srcStoreCode,
  INVITER: c.srcInviter,
  CHANNEL: c.srcChannel,
});

/** B1 的三个候选。文案要把代价写出来 —— 否则看起来只是三个名词。 */
const POLICIES = (c: Copy): { value: ConflictPolicy; label: string; hint: string }[] => [
  { value: "KEEP_FIRST", label: c.conflictKeepFirst, hint: c.conflictKeepFirstHint },
  { value: "OVERWRITE", label: c.conflictOverwrite, hint: c.conflictOverwriteHint },
  { value: "ASK_USER", label: c.conflictAskUser, hint: c.conflictAskUserHint },
];

const FACTORS = (c: Copy): { value: NewUserFactor; label: string; hint: string }[] => [
  { value: "DEVICE", label: c.factorDevice, hint: c.factorDeviceHint },
  { value: "PHONE", label: c.factorPhone, hint: c.factorPhoneHint },
];

export default function GrowthPage() {
  return <Suspense fallback={null}><GrowthInner /></Suspense>;
}

function GrowthInner() {
  const c = useCopy(GROWTH_COPY);
  const tabs = useNavTabs("/growth", TAB_KEYS);
  const sourceLabel = SOURCE_LABEL(c);
  const policies = POLICIES(c);
  const factors = FACTORS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [source, setSource] = useState("");
  const [conflictOnly, setConflictOnly] = useState("");
  const [riskyOnly, setRiskyOnly] = useState("");

  const canEdit = allow("growth:fission:update");
  const canRead = allow("growth:attribution:read");
  const sourceMap = useAttrSourceMap();

  const rule = useQuery({ queryKey: ["attr-rule"], queryFn: () => api.getAttributionRule(), enabled: tab === "rule" });
  const traceQ = { keyword, source, conflictOnly, riskyOnly, page, size };
  const traces = useQuery({ queryKey: ["traces", traceQ], queryFn: () => api.listAttributionTraces(traceQ), enabled: tab === "traces" });
  const fissions = useQuery({ queryKey: ["fissions"], queryFn: () => api.listFissionCampaigns({ size: 100 }), enabled: tab === "fission" });

  const [form, setForm] = useState<{
    priority: AttrSource[]; windowDays: string; conflictPolicy: ConflictPolicy; newUserFactors: NewUserFactor[];
  } | null>(null);
  const editing = form ?? (rule.data
    ? {
        priority: [...rule.data.priority],
        windowDays: String(rule.data.windowDays),
        conflictPolicy: rule.data.conflictPolicy,
        newUserFactors: [...rule.data.newUserFactors],
      }
    : null);

  const saveRule = useMutation({
    mutationFn: () =>
      api.saveAttributionRule({
        priority: editing!.priority,
        windowDays: Number(editing!.windowDays),
        conflictPolicy: editing!.conflictPolicy,
        newUserFactors: editing!.newUserFactors,
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["attr-rule"] }); setForm(null); notify.success(c.toastRuleSaved); },
  });

  const toggleFission = useMutation({
    mutationFn: (v: { fissionNo: string; enabled: boolean }) => api.setFissionEnabled(v.fissionNo, v.enabled),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["fissions"] }); notify.success(c.toastFissionSaved); },
  });

  /**
   * 表单编辑一律走**函数式更新**，不要 `setForm({ ...editing, ... })`。
   *
   * 实机踩到的 bug：连续点两个复选框时，第二次点击读到的 `editing` 还是上一次渲染的值
   * （`form` 尚未提交），于是第一次的变更被覆盖 —— 表现为"取消了两个因子，只有一个真的取消了"。
   * 输入框场景不容易触发（每次按键都重渲染），复选框连点是必然触发。
   */
  const patch = (fn: (prev: NonNullable<typeof editing>) => NonNullable<typeof editing>) =>
    setForm((prev) => fn(prev ?? editing!));

  /** 优先级上移：数组换位。做成拖拽会更好看，但列表只有三项，按钮更准也更好点。 */
  const moveUp = (i: number) => {
    if (i === 0) return;
    patch((p) => {
      const next = [...p.priority];
      [next[i - 1], next[i]] = [next[i], next[i - 1]];
      return { ...p, priority: next };
    });
  };

  const traceColumns: Column<AttributionTrace>[] = [
    { header: c.colTraceNo, cell: (t) => t.traceNo, numeric: true, align: "start" },
    { header: c.colUser, cell: (t) => t.userNickname },
    { header: c.colSource, cell: (t) => <StatusBadge map={sourceMap} value={t.source} /> },
    { header: c.colSourceRef, cell: (t) => t.sourceRef, className: "whitespace-normal", width: "16rem" },
    { header: c.colAttributedAt, cell: (t) => fmtTime(t.attributedAt) },
    { header: c.colFirstOrder, cell: (t) => t.orderNo ?? <span className="text-muted-foreground">{c.noOrder}</span> },
    {
      header: c.colConflict,
      // B1 的现实场景：同一用户被两个店铺码归因。列表要能直接筛出来，否则这条规则永远停在文档里
      cell: (t) => (t.conflictWith ? <Badge tone="warning">{fill(c.conflictWith, { no: t.conflictWith })}</Badge> : <span className="text-muted-foreground">{c.none}</span>),
    },
    {
      header: c.colRiskFlags,
      cell: (t) =>
        t.riskSignals.length
          ? <span className="flex flex-wrap gap-1">{t.riskSignals.map((s) => <Badge key={s} tone="danger">{s}</Badge>)}</span>
          : <span className="text-muted-foreground">{c.none}</span>,
    },
  ];

  const fissionColumns: Column<FissionCampaign>[] = [
    { header: c.colFissionNo, cell: (f) => f.fissionNo, numeric: true, align: "start" },
    { header: c.colName, cell: (f) => f.name },
    { header: c.colReward, cell: (f) => fill(c.rewardCoupon, { no: f.couponNo }) },
    { header: c.colInviterGets, cell: (f) => fill(c.coupons, { n: f.inviterCount }), numeric: true },
    { header: c.colInviteeGets, cell: (f) => fill(c.coupons, { n: f.inviteeCount }), numeric: true },
    { header: c.colInvited, cell: (f) => f.invitedCount, numeric: true },
    { header: c.colConverted, cell: (f) => f.convertedCount, numeric: true },
    {
      header: c.colEnabled,
      cell: (f) => (
        <Switch
          checked={f.enabled} disabled={!canEdit} aria-label={fill(c.ariaEnable, { name: f.name })}
          onChange={(v) => toggleFission.mutate({ fissionNo: f.fissionNo, enabled: v })}
        />
      ),
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "rule" && (
        <ConfigCard
          title={c.ruleTitle}
          readOnly={!canEdit && <ReadOnlyNotice what={c.ruleReadOnlyWhat} perm="growth:fission:update" className="mb-3" />}
          notice={
            c.ruleNotice
          }
          onSave={() => saveRule.mutate()}
          saving={saveRule.isPending}
          canSave={canEdit}
          updatedAt={rule.data?.updatedAt}
          updatedBy={rule.data?.updatedBy}
        >
          {editing && (
            <>
                <div>
                  <div className="mb-2 txt-strong">{c.priorityTitle}</div>
                  <div className="space-y-1">
                    {editing.priority.map((s, i) => (
                      <div key={s} className="flex items-center gap-2 rounded-field bg-secondary px-3 py-2">
                        <span className="tabular-nums text-muted-foreground">{i + 1}</span>
                        <span className="flex-1">{sourceLabel[s]}</span>
                        <Button size="sm" variant="ghost" disabled={!canEdit || i === 0} onClick={() => moveUp(i)}>{c.moveUp}</Button>
                      </div>
                    ))}
                  </div>
                  <p className="mt-1 txt-caption text-muted-foreground">
                    {fill(c.priorityHint, { n: ATTR_SOURCES.length })}
                  </p>
                </div>

                <div className="space-y-1">
                  <Label htmlFor="win" required>{c.fieldWindow}</Label>
                  <Input id="win" className="w-full" disabled={!canEdit} value={editing.windowDays}
                    onChange={(e) => patch((p) => ({ ...p, windowDays: e.target.value }))} />
                  <p className="txt-caption text-muted-foreground">
                    {fill(c.windowHint, { min: ATTR_WINDOW_MIN, max: ATTR_WINDOW_MAX })}
                  </p>
                </div>

                <div>
                  <div className="mb-2 txt-strong">{c.conflictTitle}</div>
                  <RadioGroup value={editing.conflictPolicy} onChange={(v) => patch((p) => ({ ...p, conflictPolicy: v as ConflictPolicy }))}>
                    {/* Radio 自带 desc 槽：代价说明与选项绑在一起，不会被读成两件事 */}
                    {policies.map((p) => (
                      <Radio key={p.value} value={p.value} disabled={!canEdit} label={p.label} desc={p.hint} />
                    ))}
                  </RadioGroup>
                </div>

                <div>
                  <div className="mb-2 txt-strong">{c.factorTitle}</div>
                  {factors.map((f) => (
                    <CheckboxField
                      key={f.value}
                      className="mb-2 items-start"
                      checked={editing.newUserFactors.includes(f.value)}
                      disabled={!canEdit}
                      label={
                        <span>
                          {f.label}
                          <span className="mt-0.5 block txt-caption text-muted-foreground">{f.hint}</span>
                        </span>
                      }
                      onChange={(v) =>
                        patch((p) => ({
                          ...p,
                          newUserFactors: v === true
                            ? [...p.newUserFactors, f.value]
                            : p.newUserFactors.filter((x) => x !== f.value),
                        }))
                      }
                    />
                  ))}
                  <p className="txt-caption text-muted-foreground">
                    {c.factorHint}
                  </p>
                </div>
            </>
          )}
        </ConfigCard>
      )}

      {tab === "traces" && (
        <>
          {!canRead && <ReadOnlyNotice what={c.tracesReadOnlyWhat} perm="growth:attribution:read" className="mb-3" />}
          <Notice className="mb-3">
            {c.tracesNotice}
          </Notice>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder}>
            <FilterSelect aria-label={c.filterSource} value={source} onChange={(v) => { setSource(v); setPage(1); }} options={sourceMap} allLabel={c.filterSourceAll} />
            <FilterSelect aria-label={c.filterConflict} value={conflictOnly} onChange={(v) => { setConflictOnly(v); setPage(1); }}
              options={[{ value: "1", label: c.filterConflictOnly }]} allLabel={c.filterConflictAll} />
            <FilterSelect aria-label={c.filterRisk} value={riskyOnly} onChange={(v) => { setRiskyOnly(v); setPage(1); }}
              options={[{ value: "1", label: c.filterRiskOnly }]} allLabel={c.filterRiskAll} />
          </Toolbar>
          <DataTable
            columns={traceColumns} rows={traces.data?.records} loading={traces.isLoading}
            error={traces.error} onRetry={() => traces.refetch()}
            rowKey={(t) => t.traceNo}
            empty={c.emptyTraces}
          />
          <Pagination page={page} size={size} onSize={setSize} total={traces.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "fission" && (
        <>
          <Notice className="mb-3">
            {c.fissionNotice}
          </Notice>
          <DataTable
            columns={fissionColumns} rows={fissions.data?.records} loading={fissions.isLoading}
            error={fissions.error} onRetry={() => fissions.refetch()}
            rowKey={(f) => f.fissionNo}
            empty={c.emptyFission}
          />
        </>
      )}
    </div>
  );
}
