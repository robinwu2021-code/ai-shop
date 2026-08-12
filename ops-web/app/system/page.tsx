"use client";

// 系统配置（矩阵 P-17.1）。平台端 18 个业务域的最后一个。
//
// 这一页配的东西**大多直接作用在 C 端**：默认皮肤、规则文案、灰度开关。
// 所以每一项都写清了「改了之后用户会看到什么」，而不是只给一个输入框。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { fill, useCopy } from "@/lib/use-copy";
import { SYSTEM_COPY } from "./copy";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import { C_END_THEMES, type ThemeKey } from "@/lib/stores/theme";
import { BASE_CURRENCY, type FeatureFlag, type MarketConfig } from "@/lib/types";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { IndustryTab } from "./industry-tab";
import { AuthCodeTab } from "./auth-code-tab";
import { ServiceScopeTab } from "./service-scope-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { ConfigCard } from "@/components/ui/config-card";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";

type Copy = (typeof SYSTEM_COPY)["zh"];
const TAB_KEYS = ["appearance", "market", "flags", "industry", "authCode", "scope"] as const;

export default function SystemPage() {
  return <Suspense fallback={null}><SystemInner /></Suspense>;
}

function SystemInner() {
  const { t } = useI18n();
  const c = useCopy(SYSTEM_COPY);
  const tabs = useNavTabs("/system", TAB_KEYS);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs);

  const canTheme = allow("system:theme:update");
  const canEnv = allow("system:env:switch");
  const canParam = allow("system:param:read");

  const appearance = useQuery({ queryKey: ["appearance"], queryFn: () => api.getAppearance(), enabled: tab === "appearance" });
  const texts = useQuery({ queryKey: ["rule-texts"], queryFn: () => api.getRuleTexts(), enabled: tab === "appearance" });
  const markets = useQuery({ queryKey: ["markets"], queryFn: () => api.listMarkets(), enabled: tab === "market" });
  const flags = useQuery({ queryKey: ["flags"], queryFn: () => api.listFeatureFlags(), enabled: tab === "flags" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["appearance"] });
    qc.invalidateQueries({ queryKey: ["rule-texts"] });
    qc.invalidateQueries({ queryKey: ["markets"] });
    qc.invalidateQueries({ queryKey: ["flags"] });
  };

  const { form: editingSkin, set: setSkinField, reset: resetSkin } = useEditableConfig(appearance.data, (d) => ({
    defaultSkin: d.defaultSkin,
    festivalSkin: d.festivalSkin ?? "",
  }));
  const saveSkin = useMutation({
    mutationFn: () =>
      api.saveAppearance({
        defaultSkin: editingSkin!.defaultSkin,
        festivalSkin: (editingSkin!.festivalSkin || undefined) as ThemeKey | undefined,
        festivalFrom: appearance.data?.festivalFrom,
        festivalTo: appearance.data?.festivalTo,
        fallbackLang: appearance.data?.fallbackLang ?? "zh",
      }),
    onSuccess: () => { invalidate(); resetSkin(); notify.success(c.toastSkinSaved); },
  });

  const { form: editingTexts, set: setTextField, reset: resetTexts } = useEditableConfig(texts.data, (d) => ({
    refund: d.refund, pickup: d.pickup, weighDiff: d.weighDiff,
  }));
  const saveTexts = useMutation({
    mutationFn: () => api.saveRuleTexts(editingTexts!),
    onSuccess: () => { invalidate(); resetTexts(); notify.success(c.toastTextsSaved); },
  });

  const [rateForm, setRateForm] = useState<Record<string, string>>({});
  const saveRate = useMutation({
    mutationFn: (v: { code: string; rate: number; enabled: boolean }) => api.saveMarketRate(v.code, v.rate, v.enabled),
    onSuccess: () => { invalidate(); notify.success(c.toastMarketSaved); },
  });

  const [flagForm, setFlagForm] = useState<Record<string, string>>({});
  const saveFlag = useMutation({
    mutationFn: (v: { key: string; enabled: boolean; percent: number }) => api.saveFeatureFlag(v.key, v.enabled, v.percent),
    onSuccess: () => { invalidate(); notify.success(c.toastFlagSaved); },
  });

  const marketColumns: Column<MarketConfig>[] = [
    { header: c.colMarket, cell: (m) => `${m.name}（${m.code}）` },
    { header: c.colCurrency, cell: (m) => m.currency },
    { header: c.colTimezone, cell: (m) => m.timezone },
    {
      header: c.colRate,
      numeric: true,
      // 基准货币的汇率是整套换算的原点，锁死而不是让人改了再报错
      cell: (m) =>
        m.currency === BASE_CURRENCY ? (
          <span className="flex items-center justify-end gap-2">
            <span className="tabular-nums">1</span>
            <Badge tone="info">{c.baseCurrencyBadge}</Badge>
          </span>
        ) : (
          <Input
            className="w-24" aria-label={fill(c.ariaRate, { code: m.code })} disabled={!canParam}
            value={rateForm[m.code] ?? String(m.rate)}
            onChange={(e) => setRateForm((p) => ({ ...p, [m.code]: e.target.value }))}
          />
        ),
    },
    {
      header: c.colEnabled,
      cell: (m) => (
        <Switch checked={m.enabled} disabled={!canParam} aria-label={fill(c.ariaEnable, { name: m.name })}
          onChange={(v) => saveRate.mutate({ code: m.code, rate: Number(rateForm[m.code] ?? m.rate), enabled: v })} />
      ),
    },
    {
      header: c.colActions,
      cell: (m) =>
        m.currency === BASE_CURRENCY || !canParam ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <Button size="sm" variant="outline"
            onClick={() => saveRate.mutate({ code: m.code, rate: Number(rateForm[m.code] ?? m.rate), enabled: m.enabled })}>
            {c.btnSaveRate}
          </Button>
        ),
    },
  ];

  const flagColumns: Column<FeatureFlag>[] = [
    { header: c.colFlag, cell: (f) => f.name },
    { header: c.colKey, cell: (f) => <code className="txt-caption">{f.key}</code> },
    {
      header: c.colPercent,
      numeric: true,
      cell: (f) => (
        <Input
          className="w-24" aria-label={fill(c.ariaPercent, { name: f.name })} disabled={!canEnv}
          value={flagForm[f.key] ?? String(f.rolloutPercent)}
          onChange={(e) => setFlagForm((p) => ({ ...p, [f.key]: e.target.value }))}
        />
      ),
    },
    {
      header: c.colEnabled,
      cell: (f) => (
        <Switch checked={f.enabled} disabled={!canEnv} aria-label={fill(c.ariaEnable, { name: f.name })}
          onChange={(v) => saveFlag.mutate({ key: f.key, enabled: v, percent: Number(flagForm[f.key] ?? f.rolloutPercent) })} />
      ),
    },
    { header: c.colUpdatedAt, cell: (f) => fmtTime(f.updatedAt) },
    {
      header: c.colActions,
      cell: (f) =>
        canEnv ? (
          <Button size="sm" variant="outline"
            onClick={() => saveFlag.mutate({ key: f.key, enabled: f.enabled, percent: Number(flagForm[f.key] ?? f.rolloutPercent) })}>
            {c.btnSavePercent}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "appearance" && (
        <div className="grid gap-4 lg:grid-cols-2">
          <ConfigCard
            className="max-w-none"
            title={c.cardSkin}
            readOnly={!canTheme && <ReadOnlyNotice what={c.skinReadOnlyWhat} perm="system:theme:update" className="mb-3" />}
            notice={c.skinNotice}
            onSave={() => saveSkin.mutate()}
            saveLabel={c.skinSaveLabel}
            saving={saveSkin.isPending}
            canSave={canTheme}
          >
            {editingSkin && (
              <>
                  <div className="space-y-1">
                    <Label htmlFor="sk-default" required>{c.fieldDefaultSkin}</Label>
                    <Select id="sk-default" className="w-full" disabled={!canTheme} value={editingSkin.defaultSkin}
                      onChange={(e) => setSkinField("defaultSkin", e.target.value as ThemeKey)}>
                      {C_END_THEMES.map((s) => <option key={s.key} value={s.key}>{t(`theme.${s.key}`)}</option>)}
                    </Select>
                    <p className="txt-caption text-muted-foreground">
                      {c.defaultSkinHint}
                    </p>
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="sk-fest">{c.fieldFestivalSkin}</Label>
                    <Select id="sk-fest" className="w-full" disabled={!canTheme} value={editingSkin.festivalSkin}
                      onChange={(e) => setSkinField("festivalSkin", e.target.value)}>
                      <option value="">{c.festivalOff}</option>
                      {C_END_THEMES.map((s) => <option key={s.key} value={s.key}>{t(`theme.${s.key}`)}</option>)}
                    </Select>
                    <p className="txt-caption text-muted-foreground">
                      {fill(c.festivalRange, { from: fmtTime(appearance.data?.festivalFrom), to: fmtTime(appearance.data?.festivalTo) })}
                    </p>
                  </div>
              </>
            )}
          </ConfigCard>

          <ConfigCard
            className="max-w-none"
            title={c.cardTexts}
            notice={c.textsNotice}
            onSave={() => saveTexts.mutate()}
            saving={saveTexts.isPending}
            canSave={canParam}
            updatedAt={texts.data?.updatedAt}
            updatedBy={texts.data?.updatedBy}
          >
            {editingTexts && (
              <>
                  {([
                    ["refund", c.textRefund],
                    ["pickup", c.textPickup],
                    ["weighDiff", c.textWeighDiff],
                  ] as const).map(([k, label]) => (
                    <div key={k} className="space-y-1">
                      <Label htmlFor={`rt-${k}`} required>{label}</Label>
                      <Textarea value={editingTexts[k]} disabled={!canParam}
                        onChange={(v) => setTextField(k, v)} />
                    </div>
                  ))}
              </>
            )}
          </ConfigCard>
        </div>
      )}

      {tab === "market" && (
        <>
          <Notice className="mb-3">
            {c.marketNotice}
            {fill(c.baseCurrencyNotice, { cur: BASE_CURRENCY })}
          </Notice>
          <DataTable
            columns={marketColumns} rows={markets.data} loading={markets.isLoading}
            error={markets.error} onRetry={() => markets.refetch()}
            rowKey={(m) => m.code}
            empty={c.marketEmpty}
          />
        </>
      )}

      {/* 主数据三块都接了真后端（其余 tab 仍走 mock） */}
      {tab === "industry" && <IndustryTab c={c} canWrite={canEnv} />}
      {/* 授权码字典改的是「一共有哪些门槛」，与类目树同权限（category:manage） */}
      {tab === "authCode" && <AuthCodeTab c={c} canWrite={allow("category:manage")} />}
      {tab === "scope" && <ServiceScopeTab c={c} canWrite={canEnv} />}

      {tab === "flags" && (
        <>
          {!canEnv && <ReadOnlyNotice what={c.flagsReadOnlyWhat} perm="system:env:switch" note={c.flagsReadOnlyNote} className="mb-3" />}
          <Notice className="mb-3">
            {c.flagsNotice}
          </Notice>
          <DataTable
            columns={flagColumns} rows={flags.data} loading={flags.isLoading}
            error={flags.error} onRetry={() => flags.refetch()}
            rowKey={(f) => f.key}
            empty={c.flagsEmpty}
          />
        </>
      )}
    </div>
  );
}
