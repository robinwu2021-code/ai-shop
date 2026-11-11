"use client";

// 平台收款档案（按币种）+ 台湾三家线上通道状态。
//
// 档案每次修改生成新版本；**已发出的付款指引按原版本**，改账户不会改写买家手里那份。
// 付款窗口由档案配置（开发示例 24 小时），下单时冻结为 paymentDueAt，不续期。
//
// 三家线上通道（NewebPay / LINE Pay / JKOPay）**未接通**：没有真实协议适配、没有商户串接验收。
// 这里写死「未接通」且开关不可点 —— 不从任何接口读一个能被改成 true 的布尔。
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable } from "@/components/ui/data-table";
import { Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { CheckboxField } from "@/components/ui/checkbox";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { TW_ONLINE_CHANNELS, showErr, type MallCopy } from "./shared";

const CURRENCIES = ["TWD", "CNY"];
const HOUR = 3_600_000;

interface Form {
  profileNo: string; platformMerchantNo: string; bankCode: string; bankAccount: string; accountName: string;
  paymentWindowVersion: string; windowHours: string; enabled: boolean; reason: string;
}

export function CollectionTab({ c, canWrite }: { c: MallCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [currency, setCurrency] = React.useState("TWD");
  const q = useQuery({ queryKey: ["mall-profile", currency], queryFn: () => api.offlineProfile(currency) });
  const [form, setForm] = React.useState<Form | null>(null);
  const terms = q.data;

  React.useEffect(() => {
    if (q.isLoading || q.error) return;
    setForm(terms
      ? { profileNo: terms.profileNo, platformMerchantNo: terms.platformMerchantNo, bankCode: terms.instructions.bankCode ?? "",
          bankAccount: terms.instructions.bankAccount ?? "", accountName: terms.instructions.accountName ?? "",
          paymentWindowVersion: terms.paymentWindowVersion, windowHours: String(terms.paymentWindowMs / HOUR), enabled: terms.enabled, reason: "" }
      : { profileNo: "", platformMerchantNo: "", bankCode: "", bankAccount: "", accountName: "", paymentWindowVersion: "", windowHours: "24", enabled: true, reason: "" });
  }, [terms, q.isLoading, q.error]);

  const windowMs = form ? Math.round(Number(form.windowHours) * HOUR) : 0;
  const save = useMutation({
    mutationFn: (f: Form) => api.configureOfflineProfile({
      profileNo: f.profileNo.trim(), expectedVersion: terms?.profileVersion ?? 0, platformMerchantNo: f.platformMerchantNo.trim(), currency,
      bankCode: f.bankCode.trim(), bankAccount: f.bankAccount.trim(), accountName: f.accountName.trim(),
      paymentWindowVersion: f.paymentWindowVersion.trim(), paymentWindowMs: windowMs, enabled: f.enabled, reason: f.reason.trim(),
    }),
    onSuccess: (t) => { qc.invalidateQueries({ queryKey: ["mall-profile", currency] }); notify.success(fill(c.toastProfileSaved, { v: t.profileVersion })); },
  });
  const valid = form && form.profileNo.trim() && form.platformMerchantNo.trim() && form.bankCode.trim() && form.bankAccount.trim()
    && form.accountName.trim() && form.paymentWindowVersion.trim() && form.reason.trim() && windowMs >= 1000 && windowMs <= 90 * 24 * HOUR;


  return (
    <div className="space-y-6">
      <HelpNote>{c.collectionHelp}</HelpNote>
      {!canWrite && <ReadOnlyNotice what={c.profileWriteWhat} perm="commerce:collection:profile:write" />}
      <div className="flex items-center gap-2">
        <label htmlFor="pf-cur" className="txt-label text-muted-foreground">{c.fieldCurrency}</label>
        <Select id="pf-cur" value={currency} onChange={(e) => { setCurrency(e.target.value); setForm(null); }}>
          {CURRENCIES.map((x) => <option key={x} value={x}>{x}</option>)}
        </Select>
      </div>
      {q.error ? (
        <DataTable columns={[]} rows={undefined} error={showErr(q.error)} onRetry={() => q.refetch()} rowKey={() => ""} empty={c.profileEmpty} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <ConfigCard className="max-w-none" title={terms ? fill(c.profileCardTitle, { v: terms.profileVersion }) : c.profileCardNew}
            notice={terms ? c.profileVersionNotice : c.profileNewNotice} onSave={() => form && save.mutate(form)} saving={save.isPending}
            canSave={canWrite && !!valid && !q.isFetching} saveLabel={terms ? c.btnSaveNewVersion : c.btnCreateProfile}>
            {form && (
              <>
                <div className="space-y-1">
                  <Label htmlFor="pf-no" required>{c.fieldProfileNo}</Label>
                  <Input id="pf-no" value={form.profileNo} disabled={!canWrite || !!terms} onChange={(e) => setForm((f) => f && { ...f, profileNo: e.target.value })} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="pf-merchant" required>{c.fieldPlatformMerchant}</Label>
                  <Input id="pf-merchant" value={form.platformMerchantNo} disabled={!canWrite} onChange={(e) => setForm((f) => f && { ...f, platformMerchantNo: e.target.value })} />
                  <p className="txt-caption text-muted-foreground">{c.platformMerchantHint}</p>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="pf-bank" required>{c.fieldBankCode}</Label>
                  <Input id="pf-bank" value={form.bankCode} disabled={!canWrite} onChange={(e) => setForm((f) => f && { ...f, bankCode: e.target.value })} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="pf-acct" required>{c.fieldBankAccount}</Label>
                  <Input id="pf-acct" value={form.bankAccount} disabled={!canWrite} onChange={(e) => setForm((f) => f && { ...f, bankAccount: e.target.value })} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="pf-name" required>{c.fieldAccountName}</Label>
                  <Input id="pf-name" value={form.accountName} disabled={!canWrite} onChange={(e) => setForm((f) => f && { ...f, accountName: e.target.value })} />
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  <div className="space-y-1">
                    <Label htmlFor="pf-win" required>{c.fieldWindowHours}</Label>
                    <Input id="pf-win" inputMode="decimal" value={form.windowHours} disabled={!canWrite} invalid={windowMs < 1000 || windowMs > 90 * 24 * HOUR}
                      onChange={(e) => setForm((f) => f && { ...f, windowHours: e.target.value })} />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="pf-winv" required>{c.fieldWindowVersion}</Label>
                    <Input id="pf-winv" value={form.paymentWindowVersion} disabled={!canWrite} onChange={(e) => setForm((f) => f && { ...f, paymentWindowVersion: e.target.value })} />
                  </div>
                </div>
                <CheckboxField label={c.fieldProfileEnabled} checked={form.enabled} disabled={!canWrite} onChange={(v) => setForm((f) => f && { ...f, enabled: v === true })} />
                <div className="space-y-1">
                  <Label htmlFor="pf-reason" required>{c.fieldReason}</Label>
                  <Textarea id="pf-reason" value={form.reason} disabled={!canWrite} placeholder={c.reasonPh} onChange={(v) => setForm((f) => f && { ...f, reason: v })} />
                </div>
              </>
            )}
          </ConfigCard>
          <div className="space-y-3">
            {terms ? (
              <div className="rounded-card border border-border bg-card p-4">
                <div className="mb-2 txt-strong">{c.profileCurrent}</div>
                <FieldGrid>
                  <Field label={c.fieldProfileNo}>{terms.profileNo}</Field>
                  <Field label={c.colVersion}>{`v${terms.profileVersion}`}</Field>
                  <Field label={c.fieldPlatformMerchant}>{terms.platformMerchantNo}</Field>
                  <Field label={c.fieldAccountKey}><code className="txt-caption">{terms.receivingAccountKey}</code></Field>
                  <Field label={c.fieldWindowHours}>{fill(c.hoursN, { n: terms.paymentWindowMs / HOUR })}</Field>
                  <Field label={c.fieldWindowVersion}>{terms.paymentWindowVersion}</Field>
                </FieldGrid>
                <Notice tone={terms.enabled ? "info" : "warning"}>{terms.enabled ? c.profileActive : c.profileDisabled}</Notice>
              </div>
            ) : <Notice tone="warning">{fill(c.profileMissing, { cur: currency })}</Notice>}
          </div>
        </div>
      )}

      <section className="space-y-2">
        <div className="txt-strong">{c.secChannels}</div>
        <Notice tone="warning">{c.channelsNotice}</Notice>
        {/* 常量行，不来自任何接口：所以不是表格查询，也就没有错误态 */}
        <ul className="divide-y divide-border rounded-card border border-border">
          {TW_ONLINE_CHANNELS.map((n) => (
            <li key={n} className="flex items-center justify-between gap-3 p-3">
              <span className="txt-strong">{n}</span>
              <span className="flex items-center gap-3">
                <Badge tone="muted">{c.channelNotConnected}</Badge>
                <Switch checked={false} disabled aria-label={fill(c.channelEnableAria, { n })} onChange={() => undefined} />
              </span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
