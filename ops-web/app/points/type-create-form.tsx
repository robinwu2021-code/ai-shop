"use client";

import { useState } from "react";
import { Input, Select } from "@/components/ui/input";
import { usePointsText } from "@/lib/points-i18n";
import { pointsCurrencies, pointsCurrency } from "@/lib/points-currency";

export type PointsTypeDraft = {
  typeCode: string; name: string; currency: string;
  rate: { pointsUnit: number; minorUnit: number }; remark: string;
};
export function PointsTypeCreateForm({ existing, onSubmit, pending = false }: {
  existing: { typeCode: string; name: string }[]; onSubmit: (draft: PointsTypeDraft) => void; pending?: boolean;
}) {
  const { t } = usePointsText();
  const [currency, setCurrency] = useState("CNY"), [unitCode, setUnitCode] = useState("fen"), [points, setPoints] = useState("1");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const c = pointsCurrency(currency)!, unit = c.units.find((u) => u.code === unitCode)!;
  const integer = (s: string) => /^\d+$/.test(s) && Number.isSafeInteger(Number(s)) && Number(s) > 0;
  const label = "flex min-w-0 flex-col gap-2 txt-label";
  function error(id: string) { return errors[id] && <span id={`points-create-${id}-error`} className="txt-caption text-destructive" role="alert">{errors[id]}</span>; }
  function clear(id: string) { setErrors((prev) => ({ ...prev, [id]: "" })); }
  return <form id="points-type-create" className="space-y-5" onSubmit={(event) => {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    const name = String(form.get("name") ?? "").trim(), typeCode = String(form.get("code") ?? "").trim();
    const next: Record<string, string> = {};
    for (const [id, value, zh, en] of [["name", name, "名称", "name"], ["code", typeCode, "代码", "code"]]) {
      if (!value) next[id] = t(`请填写积分${zh}。`, `Enter a points ${en}.`);
      else if (existing.some((r) => (id === "name" ? r.name : r.typeCode).toLowerCase() === value.toLowerCase())) next[id] = t(`积分${zh}已存在，请修改。`, `This points ${en} already exists.`);
    }
    if (!integer(points)) next.points = t("请输入大于 0 的整数积分数量。", "Enter a positive whole number of points.");
    setErrors(next); if (Object.keys(next).length) return;
    onSubmit({ name, typeCode, currency, rate: { pointsUnit: Number(points), minorUnit: unit.minor }, remark: String(form.get("remark") ?? "").trim() });
  }}>
    <div className="grid max-w-[510px] grid-cols-1 gap-4 sm:grid-cols-2">
      <label className={label}>{t("积分名称", "Points name")}<Input name="name" required maxLength={80} placeholder={t("例如：商城积分", "e.g. Store points")} invalid={!!errors.name} onInput={() => clear("name")} disabled={pending} />{error("name")}</label>
      <label className={label}>{t("积分代码", "Points code")}<Input dir="ltr" name="code" required maxLength={40} placeholder="MALL_POINTS" invalid={!!errors.code} onInput={() => clear("code")} disabled={pending} />{error("code")}</label>
    </div>
    <fieldset disabled={pending} className="space-y-3">
      <legend className="txt-label font-semibold">{t("兑换配置", "Conversion settings")}</legend>
      <div className="flex flex-wrap items-start gap-4">
        <div className="grid w-full max-w-[346px] grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] gap-4">
          <label className={label}>{t("货币类型", "Currency")}<Select name="currency" className="w-full min-w-0" value={currency} onChange={(e) => {
            const next = pointsCurrency(e.target.value)!; setCurrency(next.code); setUnitCode(next.units[0].code); setPoints("1"); clear("points");
          }}>{pointsCurrencies.map((v) => <option key={v.code} value={v.code}>{t(v.name, v.english)}（{v.code}）</option>)}</Select></label>
          <label className={label}>{t("货币单位", "Currency unit")}<Select name="unit" className="w-full min-w-0" value={unitCode} onChange={(e) => {
            const next = c.units.find((v) => v.code === e.target.value)!;
            if (!integer(points)) { setErrors((prev) => ({ ...prev, points: t("请先输入有效的整数积分。", "Enter valid whole points first.") })); return; }
            const numerator = BigInt(points) * BigInt(next.minor), denominator = BigInt(unit.minor);
            if (numerator % denominator !== 0n || numerator / denominator > BigInt(Number.MAX_SAFE_INTEGER)) {
              setErrors((prev) => ({ ...prev, points: t("该单位无法用有效整数积分表示当前比例，请先调整积分数量。", "This unit cannot represent the rate as valid whole points. Adjust the points first.") })); return;
            }
            setPoints(String(numerator / denominator)); setUnitCode(next.code); clear("points");
          }}>{c.units.map((v) => <option key={v.code} value={v.code}>{t(v.name, v.english)}</option>)}</Select></label>
        </div>
        <label className={`${label} w-[140px]`}>{t("兑换积分数量", "Points amount")}<Input name="points" type="number" inputMode="numeric" min={1} max={Number.MAX_SAFE_INTEGER} step={1} required value={points} invalid={!!errors.points} aria-describedby={errors.points ? "points-create-points-error" : undefined} onChange={(e) => { setPoints(e.target.value); clear("points"); }} /></label>
      </div>{error("points")}
    </fieldset>
    <label className={label}>{t("备注", "Remark")}<textarea name="remark" maxLength={500} rows={3} disabled={pending} placeholder={t("填写积分用途或补充说明（选填）", "Purpose or additional notes (optional)")} className="focus-ring w-full resize-y rounded-field bg-secondary px-3.5 py-2 txt-body" /></label>
  </form>;
}
