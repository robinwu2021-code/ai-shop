"use client";

// 台湾主流渠道健康卡片（《支付中心-台湾主流渠道运营需求》§4.3）：放在审核待办页头下。
// 每家一张：近 1 小时调用成功率（成功笔数 / 总笔数，两次只取总数的查询）、未处理告警（OPEN + CLAIMED，按渠道范围归类）、30 天内到期的商户密钥。
// 指标按权限显示：没有对应查看权限就不发请求、不显示那一项。
import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { payCenterHref } from "@/app/payment-center/module";
import { useCan } from "@/lib/use-can";
import { useCopy, fill } from "@/lib/use-copy";
import { usePayCenterApi } from "./shared";
import { TW_COPY, type TwCopy } from "./tw-copy";

/** 台湾主流渠道（运营管理范围）：渠道码 → 该渠道商户密钥类型 */
export const TW_CHANNELS = ["NEWEBPAY", "LINEPAY", "JKOPAY"] as const;
export type TwChannel = (typeof TW_CHANNELS)[number];
const KEY_TYPE: Record<TwChannel, string> = { NEWEBPAY: "NEWEBPAY_MERCHANT_HASH", LINEPAY: "LINEPAY_MERCHANT_SIGN", JKOPAY: "JKOPAY_MERCHANT_SIGN" };
export const twChannelLabel = (m: TwCopy, ch: string) => (m as unknown as Record<string, string>)[`ch_${ch}`] ?? ch;

/** 成功率：总笔数为 0 时返回 null（显示「近 1 小时没有调用」），不当成 0% */
export function successRate(ok: number, all: number): number | null {
  return all > 0 ? Math.round((ok / all) * 1000) / 10 : null;
}

/** 未处理告警按渠道归类：只认渠道范围（scopeType=CHANNEL）且 scopeRef 是渠道码的告警 */
export function alertsByChannel(rows: { scopeType: string; scopeRef: string | null }[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const r of rows) if (r.scopeType === "CHANNEL" && r.scopeRef) out[r.scopeRef] = (out[r.scopeRef] ?? 0) + 1;
  return out;
}

async function callStats(api: PayCenterApi, ch: string) {
  const from = Date.now() - 3600_000;
  const [all, ok] = await Promise.all([
    api.traces.calls({ payChannel: ch, from, page: 1, size: 1 }),
    api.traces.calls({ payChannel: ch, from, status: "SUCCEEDED", page: 1, size: 1 }),
  ]);
  return { all: all.total, ok: ok.total };
}

export function TwChannelHealth() {
  const api = usePayCenterApi();
  const m = useCopy(TW_COPY);
  const allow = useCan();
  const canCalls = allow("pay:trace:view");
  const canAlerts = allow("pay:alert-event:view");
  const canKeys = allow("pay:credential:view");
  const opts = { staleTime: 60_000, retry: false } as const;
  const calls = useQuery({ queryKey: ["pay-center", "tw-health", "calls", api.source], queryFn: async () => Object.fromEntries(await Promise.all(TW_CHANNELS.map(async (ch) => [ch, await callStats(api, ch)] as const))), enabled: canCalls, ...opts });
  const alerts = useQuery({
    queryKey: ["pay-center", "tw-health", "alerts", api.source], enabled: canAlerts, ...opts,
    queryFn: async () => {
      const [open, claimed] = await Promise.all([api.alerts.events({ status: "OPEN", page: 1, size: 100 }), api.alerts.events({ status: "CLAIMED", page: 1, size: 100 })]);
      return alertsByChannel([...open.records, ...claimed.records]);
    },
  });
  const keys = useQuery({ queryKey: ["pay-center", "tw-health", "keys", api.source], queryFn: async () => Object.fromEntries(await Promise.all(TW_CHANNELS.map(async (ch) => [ch, (await api.credentials.list({ credentialType: KEY_TYPE[ch], expiringWithinDays: 30, page: 1, size: 1 })).total] as const))), enabled: canKeys, ...opts });
  if (!canCalls && !canAlerts && !canKeys) return null;
  return (
    <section className="mt-3 space-y-2" aria-label={m.healthTitle}>
      <h2 className="txt-label text-muted-foreground">{m.healthTitle}</h2>
      <div className="grid gap-3 sm:grid-cols-3">
        {TW_CHANNELS.map((ch) => {
          const st = calls.data?.[ch];
          const rate = st ? successRate(st.ok, st.all) : null;
          return (
            <article key={ch} className="space-y-2 rounded-card border border-border p-3">
              <h3 className="txt-label">{twChannelLabel(m, ch)}</h3>
              <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 txt-body">
                {canCalls && <>
                  <dt className="text-muted-foreground">{m.successRate}</dt>
                  <dd className="text-right">{calls.error ? m.healthUnavailable : !st ? "…" : rate == null ? m.noCalls : <>{rate}%<span className="ml-1 txt-caption text-muted-foreground">{fill(m.successDetail, { ok: st.ok, all: st.all })}</span></>}</dd>
                </>}
                {canAlerts && <>
                  <dt className="text-muted-foreground">{m.openAlerts}</dt>
                  <dd className="text-right">{alerts.error ? m.healthUnavailable : alerts.data ? alerts.data[ch] ?? 0 : "…"}</dd>
                </>}
                {canKeys && <>
                  <dt className="text-muted-foreground">{m.expiringKeys}</dt>
                  <dd className="text-right">{keys.error ? m.healthUnavailable : keys.data ? keys.data[ch] ?? 0 : "…"}</dd>
                </>}
              </dl>
              <div className="flex flex-wrap gap-3 txt-caption">
                {canCalls && <Link className="focus-ring text-primary-ink underline" href={payCenterHref("channel-calls")}>{m.viewCalls}</Link>}
                {canAlerts && <Link className="focus-ring text-primary-ink underline" href={payCenterHref("alert-events")}>{m.viewAlerts}</Link>}
                {canKeys && <Link className="focus-ring text-primary-ink underline" href={payCenterHref("credentials")}>{m.viewKeys}</Link>}
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}
