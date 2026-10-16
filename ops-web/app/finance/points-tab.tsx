"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useLocaleStore } from "@/lib/stores/locale";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import type { FINANCE_COPY } from "./copy";

type Copy = (typeof FINANCE_COPY)["zh"];

export function PointsTab({ c }: { c: Copy }) {
  const locale = useLocaleStore((s) => s.locale);
  const q = useQuery({ queryKey: ["points-overview", "CN"], queryFn: () => api.pointsOverview("CN") });
  const d = q.data;
  if (q.isError) return <Notice tone="danger">{c.ptLoadError} <Button onClick={() => q.refetch()}>{c.ptRetry}</Button></Notice>;
  if (!d) return <Notice tone="muted">{c.ptLoading}</Notice>;
  const amount = (value: number) => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", {
    style: "currency", currency: d.currency,
  }).format(value / 10 ** d.scale);
  const cards = [
    [c.ptU, d.uMinor], [c.ptC, d.cMinor], [c.ptD, d.dMinor], [c.ptB, d.bMinor],
    [c.ptReservedB, d.reservedBMinor], [c.ptLockedU, d.lockedUMinor],
    [c.ptLockedB, d.lockedBMinor], [c.ptR, d.receivableMinor],
  ] as const;
  return <div className="space-y-4">
    <Notice tone="muted">{c.ptHint}</Notice>
    <p>{d.typeCode} · {d.market} · {d.currency}</p>
    <p>{c.ptRate}: {d.ratePoints} / {d.rateMinor}</p>
    <div className="grid gap-3 sm:grid-cols-4">
      {cards.map(([label, value]) => <div key={label} className="rounded-card border p-3">
        <div className="txt-caption text-muted-foreground">{label}</div>
        <div className="mt-1 txt-title tabular-nums">{amount(value)}</div>
      </div>)}
    </div>
  </div>;
}
