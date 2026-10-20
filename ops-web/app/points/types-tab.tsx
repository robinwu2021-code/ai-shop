"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsCreate, PointsFallbackChange, PointsTypeChange, PointsTypeConfig } from "@/lib/types";
import { PointsTypeCreateForm } from "./type-create-form";
import { pointsCurrency } from "@/lib/points-currency";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";

type Command = { kind: "CREATE"; value: PointsCreate } | { kind: "STATE"; value: PointsTypeChange } |
  { kind: "RULE"; value: PointsFallbackChange };

export function PointsTypesTab({ canEdit }: { canEdit: boolean }) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selectedCode, setSelectedCode] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const selected = types.data?.find((item) => item.typeCode === selectedCode);
  const fallback = useQuery({ queryKey: ["points-fallback", selectedCode], queryFn: () => api.pointsFallback(selectedCode), enabled: !!selectedCode });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation({
    mutationFn: (command: Command) => {
      switch (command.kind) {
        case "CREATE": return api.createPointsType(command.value);
        case "STATE": return api.transitionPointsType(command.value);
        case "RULE": return api.savePointsFallback(command.value);
      }
    },
    onSuccess: (_result, command) => {
      intent.current = undefined;
      void qc.invalidateQueries({ queryKey: ["points-types"] });
      void qc.invalidateQueries({ queryKey: ["points-fallback"] });
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
    { header: t("积分名称", "Points name"), cell: (row: PointsTypeConfig) => row.name },
    { header: t("积分代码", "Points code"), cell: (row: PointsTypeConfig) => <span dir="ltr">{row.typeCode}</span> },
    { header: t("货币类型", "Currency"), cell: (row: PointsTypeConfig) => { const c = pointsCurrency(row.currency); return c ? t(c.name, c.english) : row.currency; } },
    { header: t("货币代码", "Currency code"), cell: (row: PointsTypeConfig) => row.currency },
    { header: t("货币单位", "Currency unit"), cell: (row: PointsTypeConfig) => { const unit = pointsCurrency(row.currency)?.units[0]; return unit ? t(unit.name, unit.english) : t("最小货币单位", "Minor unit"); } },
    { header: t("货币单位数量", "Currency units"), numeric: true, cell: (row: PointsTypeConfig) => row.rate.minorUnit },
    { header: t("兑换积分数量", "Points amount"), numeric: true, cell: (row: PointsTypeConfig) => row.rate.pointsUnit },
    { header: t("抵扣上限", "Deduction cap"), numeric: true, cell: (row: PointsTypeConfig) => `${(row.deductRatioBp / 100).toFixed(2)}%` },
    { header: t("状态", "Status"), cell: (row: PointsTypeConfig) => t(
      row.status === "DRAFT" ? "草稿" : row.status === "ACTIVE" ? "生效中" : row.status === "DRAINING" ? "清偿中" : "已退役", row.status,
    ) },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = types.error || fallback.error;

  return <div className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <SectionHeader title={t("积分设置", "Points settings")} summary={types.data?.length ?? 0} className="mb-0" />
      {canEdit && <Button onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button>}
    </div>
    <Notice>{t("金额使用类型币种的最小货币单位。配置只影响后续交易，旧交易保留原比例和规则快照。所有门店默认参与，本期不在此维护单店签约或接入。", "Amounts use the type's minor currency unit. Configuration affects future transactions; existing transactions keep their original rate and rules. All stores participate by default; store agreements and per-store enrollment are not managed here.")}</Notice>
    <DataTable columns={columns} rows={types.data} loading={types.isPending} error={types.error} onRetry={() => void types.refetch()}
      rowKey={(row) => row.typeCode} empty={t("还没有积分类型，请先新建。", "No points types yet. Create one to get started.")}
      emptyAction={canEdit ? <Button size="sm" onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button> : undefined}
      rowProps={(row) => ({ role: "button", tabIndex: 0, "aria-label": `${t("查看积分类型", "View points type")} ${row.name}`,
        className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
        onClick: () => { setSelectedCode(row.typeCode); save.reset(); },
        onKeyDown: (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedCode(row.typeCode); save.reset(); } },
      })} />

    <Drawer open={createOpen} onOpenChange={(open) => { setCreateOpen(open); if (!open) save.reset(); }}
      title={t("新增积分类型", "New points type")}
      desc={t("设置积分名称、代码和兑换配置。创建后可在详情中激活。", "Set the name, code and conversion settings. Activate it from details after creation.")}
      width="w-[640px]"
      footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{t("取消", "Cancel")}</Button><Button type="submit" form="points-type-create" disabled={save.isPending}>{t("创建积分", "Create points")}</Button></>}>
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      <PointsTypeCreateForm existing={types.data ?? []} pending={save.isPending} onSubmit={(draft) => {
        submit({ kind: "CREATE", value: { requestId: "", command: {
          typeCode: draft.typeCode, name: draft.name, currency: draft.currency, rate: draft.rate,
        }, reason: draft.remark || t("创建积分类型", "Create points type") } });
      }} />
    </Drawer>

    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) { setSelectedCode(""); save.reset(); } }}
      title={selected ? `${selected.name} · ${selected.typeCode} · ${selected.status}` : ""}
      desc={t("维护积分类型状态和兜底规则。", "Manage points type state and fallback rule.")}
      width="w-[760px]">
      {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void fallback.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      {save.isSuccess && <Notice>{t("已保存，操作号：", "Saved, request ID: ")}{save.data.requestId}</Notice>}
      {selected && <div className={card}>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-3 txt-body">
          <dt className="text-muted-foreground">{t("积分名称", "Points name")}</dt><dd>{selected.name}</dd>
          <dt className="text-muted-foreground">{t("积分代码", "Points code")}</dt><dd dir="ltr">{selected.typeCode}</dd>
          <dt className="text-muted-foreground">{t("货币类型", "Currency")}</dt><dd>{(() => { const c = pointsCurrency(selected.currency); return c ? t(c.name, c.english) : selected.currency; })()}</dd>
          <dt className="text-muted-foreground">{t("货币代码", "Currency code")}</dt><dd>{selected.currency}</dd>
          <dt className="text-muted-foreground">{t("货币单位", "Currency unit")}</dt><dd>{(() => { const u = pointsCurrency(selected.currency)?.units[0]; return u ? t(u.name, u.english) : t("最小货币单位", "Minor unit"); })()}</dd>
          <dt className="text-muted-foreground">{t("货币单位数量", "Currency units")}</dt><dd>{selected.rate.minorUnit}</dd>
          <dt className="text-muted-foreground">{t("兑换积分数量", "Points amount")}</dt><dd>{selected.rate.pointsUnit}</dd>
          <dt className="text-muted-foreground">{t("抵扣上限", "Deduction cap")}</dt><dd>{(selected.deductRatioBp / 100).toFixed(2)}%</dd>
        </dl>
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

    </Drawer>
  </div>;
}
