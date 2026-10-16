"use client";

import { useState } from "react";
import { Select } from "@/components/ui/input";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { usePointsText } from "@/lib/points-i18n";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import type { FINANCE_COPY } from "../finance/copy";

type Copy = (typeof FINANCE_COPY)["zh"];

export function PointsTab({ c }: { c: Copy }) {
  const { locale, t } = usePointsText();
  const [selected, select] = useState("");
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const typeCode = selected || types.data?.find((t) => t.status === "ACTIVE")?.typeCode || types.data?.[0]?.typeCode || "";
  const q = useQuery({ queryKey: ["points-overview", typeCode], queryFn: () => api.pointsOverview(undefined, typeCode), enabled: !!typeCode });
  if (types.isError) return <Notice tone="danger">{types.error.message}<Button onClick={() => void types.refetch()}>{c.ptRetry}</Button></Notice>;
  if (types.data?.length === 0) return <Notice>{t("请先在积分类型与协议中创建积分类型。", "Create a points type in Points types and agreements first.")}</Notice>;
  const d = q.data;
  if (q.isError) return <Notice tone="danger">{c.ptLoadError} <Button onClick={() => q.refetch()}>{c.ptRetry}</Button></Notice>;
  if (!d) return <Notice tone="muted">{c.ptLoading}</Notice>;
  const amount = (value: number) => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale, {
    style: "currency", currency: d.currency,
  }).format(value / 10 ** d.scale);
  const cards = [
    [c.ptU, d.uMinor], [c.ptC, d.cMinor], [c.ptD, d.dMinor], [c.ptB, d.bMinor],
    [c.ptReservedB, d.reservedBMinor], [c.ptLockedU, d.lockedUMinor],
    [c.ptLockedB, d.lockedBMinor], [c.ptR, d.receivableMinor],
  ] as const;
  return <div className="space-y-4">
    <Notice tone="muted">{c.ptHint}</Notice>
    <label>{t("积分类型", "Points type")} <Select value={typeCode} onChange={(e) => select(e.target.value)}>{types.data?.map((t) => <option key={t.typeCode} value={t.typeCode}>{t.typeCode} · {t.market} · {t.status}</option>)}</Select></label>
    <p dir="auto">{d.typeCode} · {d.market} · {d.currency}</p>
    <p dir="auto">{c.ptRate}: {d.ratePoints} / {d.rateMinor}</p>
    <div className="grid gap-3 sm:grid-cols-4">
      {cards.map(([label, value]) => <div key={label} className="rounded-card border p-3">
        <div className="txt-caption text-muted-foreground">{label}</div>
        <div className="mt-1 txt-title tabular-nums">{amount(value)}</div>
      </div>)}
    </div>
  </div>;
}
