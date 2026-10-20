"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsCreate, PointsFallbackChange, PointsTermsChange, PointsTypeChange, PointsTypeConfig } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";

type Command = { kind: "CREATE"; value: PointsCreate } | { kind: "STATE"; value: PointsTypeChange } |
  { kind: "RULE"; value: PointsFallbackChange } | { kind: "TERMS"; value: PointsTermsChange };

export function PointsTypesTab({ canEdit }: { canEdit: boolean }) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selectedCode, setSelectedCode] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const selected = types.data?.find((item) => item.typeCode === selectedCode);
  const fallback = useQuery({ queryKey: ["points-fallback", selectedCode], queryFn: () => api.pointsFallback(selectedCode), enabled: !!selectedCode });
  const terms = useQuery({ queryKey: ["points-terms", selectedCode], queryFn: () => api.pointsTerms(selectedCode), enabled: !!selectedCode });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation({
    mutationFn: (command: Command) => {
      switch (command.kind) {
        case "CREATE": return api.createPointsType(command.value);
        case "STATE": return api.transitionPointsType(command.value);
        case "RULE": return api.savePointsFallback(command.value);
        case "TERMS": return api.publishPointsTerms(command.value);
      }
    },
    onSuccess: (_result, command) => {
      intent.current = undefined;
      void qc.invalidateQueries({ queryKey: ["points-types"] });
      void qc.invalidateQueries({ queryKey: ["points-fallback"] });
      void qc.invalidateQueries({ queryKey: ["points-terms"] });
      if (command.kind === "CREATE") {
        setCreateOpen(false);
        setSelectedCode(command.value.command.typeCode);
      }
    },
  });
  function submit(command: Command) {
    const next = pointsCommand({ ...command, value: { ...command.value, requestId: "" } }, intent.current);
    intent.current = next.intent;
    save.mutate({ ...command, value: { ...command.value, requestId: next.command.requestId } } as Command);
  }

  const columns = [
    { header: t("积分类型", "Points type"), cell: (row: PointsTypeConfig) => <div><p className="font-semibold">{row.typeCode}</p><p className="txt-caption text-muted-foreground">{row.market} · {row.currency}</p></div> },
    { header: t("兑换比例", "Conversion rate"), cell: (row: PointsTypeConfig) => `${row.rate.pointsUnit} ${t("积分", "points")} = ${row.rate.minorUnit} ${t("最小货币单位", "minor units")}` },
    { header: t("抵扣上限", "Deduction cap"), numeric: true, cell: (row: PointsTypeConfig) => `${(row.deductRatioBp / 100).toFixed(2)}%` },
    { header: t("闲置天数", "Idle days"), numeric: true, cell: (row: PointsTypeConfig) => row.idleDays },
    { header: t("状态", "Status"), cell: (row: PointsTypeConfig) => t(
      row.status === "DRAFT" ? "草稿" : row.status === "ACTIVE" ? "生效中" : row.status === "DRAINING" ? "清偿中" : "已退役", row.status,
    ) },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = types.error || fallback.error || terms.error;

  return <div className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <SectionHeader title={t("积分类型与规则", "Points types & rules")} summary={types.data?.length ?? 0} className="mb-0" />
      {canEdit && <Button onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button>}
    </div>
    <Notice>{t("金额使用类型币种的最小货币单位。配置只影响后续交易，旧交易保留原比例和规则快照。", "Amounts use the type's minor currency unit. Configuration affects future transactions; existing transactions keep their original rate and rules.")}</Notice>
    <p className="txt-caption text-muted-foreground"><Link href="/points?tab=configuration-legacy" className="focus-ring rounded-field text-primary-ink underline">{t("其他积分操作（临时入口）", "Other points operations (temporary entry)")}</Link></p>
    <DataTable columns={columns} rows={types.data} loading={types.isPending} error={types.error} onRetry={() => void types.refetch()}
      rowKey={(row) => row.typeCode} empty={t("还没有积分类型，请先新建。", "No points types yet. Create one to get started.")}
      emptyAction={canEdit ? <Button size="sm" onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button> : undefined}
      rowProps={(row) => ({ role: "button", tabIndex: 0, "aria-label": `${t("查看积分类型", "View points type")} ${row.typeCode}`,
        className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
        onClick: () => { setSelectedCode(row.typeCode); save.reset(); },
        onKeyDown: (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedCode(row.typeCode); save.reset(); } },
      })} />

    <Drawer open={createOpen} onOpenChange={(open) => { setCreateOpen(open); if (!open) save.reset(); }}
      title={t("新建积分类型（草稿）", "Create points type (draft)")}
      desc={t("设置市场、兑换比例与使用边界。创建后还需在详情中激活。", "Set the market, conversion rate and usage limits. Activate it from details after creation.")}
      width="w-[640px]"
      footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{t("取消", "Cancel")}</Button><Button type="submit" form="points-type-create" disabled={save.isPending}>{t("新建草稿", "Create draft")}</Button></>}>
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      <form id="points-type-create" className="space-y-4" onSubmit={(event) => {
        event.preventDefault(); const form = new FormData(event.currentTarget);
        submit({ kind: "CREATE", value: { requestId: "", command: {
          typeCode: String(form.get("code")).trim(), market: String(form.get("market")),
          rate: { pointsUnit: Number(form.get("points")), minorUnit: Number(form.get("minor")) },
          deductRatioBp: Number(form.get("ratio")), idleDays: Number(form.get("days")),
        }, reason: String(form.get("reason")).trim() } });
      }}>
        <div className="grid gap-3 md:grid-cols-2">
          <label>{t("类型代码", "Type code")}<Input dir="auto" name="code" required maxLength={40} /></label>
          <label>{t("市场代码", "Market")}<Input dir="auto" name="market" required maxLength={24} /></label>
          {[["points",t("比例：积分单位", "Rate: points unit"),1,Number.MAX_SAFE_INTEGER], ["minor",t("比例：最小货币单位", "Rate: minor currency unit"),1,Number.MAX_SAFE_INTEGER], ["ratio",t("抵扣上限（万分比）", "Deduction cap (bp)"),0,10000], ["days",t("闲置天数", "Idle days"),1,36500]].map(([name,label,min,max]) =>
            <label key={String(name)}>{label}<Input dir="auto" name={String(name)} type="number" required min={Number(min)} max={Number(max)} step={1} /></label>)}
          <label className="md:col-span-2">{t("原因", "Reason")}<Input dir="auto" name="reason" required maxLength={500} /></label>
        </div>
      </form>
    </Drawer>

    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) { setSelectedCode(""); save.reset(); } }}
      title={selected ? `${selected.typeCode} · ${selected.market} · ${selected.status}` : ""}
      desc={t("维护积分类型状态、兜底规则和受理协议。", "Manage points type status, fallback rule and acceptance terms.")}
      width="w-[760px]">
      {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void fallback.refetch(); void terms.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      {save.isSuccess && <Notice>{t("已保存，操作号：", "Saved, request ID: ")}{save.data.requestId}</Notice>}
      {selected && <div className={card}>
        <p dir="auto">{selected.currency} / {selected.scale} · {selected.rate.pointsUnit} {t("积分", "points")} = {selected.rate.minorUnit} {t("最小货币单位", "minor units")} · {t("抵扣上限（万分比）", "Deduction cap (bp)")} {selected.deductRatioBp} · {t("闲置天数", "Idle days")} {selected.idleDays}</p>
        <form className="flex flex-wrap items-end gap-3" onSubmit={(event) => {
          event.preventDefault(); const form = new FormData(event.currentTarget);
          submit({ kind: "STATE", value: { requestId: "", typeCode: selected.typeCode,
            action: String(form.get("action")) as PointsTypeChange["action"], reason: String(form.get("reason")).trim() } });
        }}>
          <label>{t("状态流转", "State transition")}<Select name="action" disabled={!canEdit || selected.status === "RETIRED"}>
            {selected.status === "DRAFT" && <option value="ACTIVATE">{t("生效", "Activate")}</option>}
            {selected.status === "ACTIVE" && <option value="DRAIN">{t("清偿停用", "Drain")}</option>}
            {selected.status === "DRAINING" && <option value="RETIRE">{t("退役（须全部清偿）", "Retire (all obligations must be cleared)")}</option>}
          </Select></label>
          <label>{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
          {canEdit && selected.status !== "RETIRED" && <Button disabled={save.isPending} type="submit">{t("提交状态变更", "Change state")}</Button>}
        </form>
      </div>}
      {selected && fallback.data && <form key={`fallback:${selectedCode}:${fallback.data.revision}`} className={card} onSubmit={(event) => {
        event.preventDefault(); const form = new FormData(event.currentTarget);
        submit({ kind: "RULE", value: { requestId: "", typeCode: selectedCode, expectedRevision: fallback.data.revision,
          mode: String(form.get("mode")) as PointsFallbackChange["mode"], value: Number(form.get("value")), reason: String(form.get("reason")).trim() } });
      }}>
        <h3 className="txt-heading">{t("平台兜底规则 · 修订", "Platform fallback rule · revision")} {fallback.data.revision}</h3>
        <p dir="auto">{t("商品例外和类目规则在商品管理维护；此处设置最终兜底。RATIO 使用万分比，FIXED 使用积分数量。", "Product exceptions and category rules are managed in Products; this is the final fallback. RATIO uses basis points, FIXED uses points.")}</p>
        <div className="flex flex-wrap gap-3">
          <label>{t("方式", "Mode")}<Select name="mode" defaultValue={fallback.data.mode} disabled={!canEdit}><option>RATIO</option><option>FIXED</option></Select></label>
          <label>{t("数值", "Value")}<Input dir="auto" name="value" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} required defaultValue={fallback.data.value} disabled={!canEdit} /></label>
          <label>{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
        </div>
        {canEdit && <Button type="submit" disabled={save.isPending}>{t("保存新修订", "Save new revision")}</Button>}
      </form>}
      {selected && terms.data !== undefined && !terms.isPending && !terms.isError && <form key={`terms:${selectedCode}:${terms.data?.version ?? "none"}`} className={card} onSubmit={(event) => {
        event.preventDefault(); const form = new FormData(event.currentTarget);
        submit({ kind: "TERMS", value: { requestId: "", typeCode: selectedCode, version: String(form.get("version")),
          text: String(form.get("text")), reason: String(form.get("reason")).trim() } });
      }}>
        <h3 className="txt-heading">{t("受理协议文本", "Acceptance terms")}</h3>
        <p dir="auto">{t("发布文本不代表门店已签署。签署及解除由商家主账号办理。", "Publishing terms does not sign for a store. Signing and termination require the merchant owner.")}</p>
        <label className="block">{t("版本", "Version")}<Input dir="auto" name="version" required defaultValue={terms.data?.version ?? ""} disabled={!canEdit} /></label>
        <label className="block">{t("正文", "Text")}<textarea dir="auto" name="text" className="focus-ring min-h-40 w-full rounded-field border border-border p-3" required defaultValue={terms.data?.text ?? ""} disabled={!canEdit} /></label>
        <p dir="auto" className="break-all txt-caption">{terms.data?.hash}</p>
        <label className="block">{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
        {canEdit && <Button disabled={save.isPending} type="submit">{t("发布版本", "Publish version")}</Button>}
      </form>}
    </Drawer>
  </div>;
}
