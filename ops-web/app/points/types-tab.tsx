"use client";

import { useRef, useState } from "react";
import { Pencil } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsCreate, PointsTypeChange, PointsTypeRename, PointsTypeConfig } from "@/lib/types";
import { PointsTypeCreateForm } from "./type-create-form";
import { pointsCurrency } from "@/lib/points-currency";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Input, Select } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";

type Command = { kind: "RENAME"; value: PointsTypeRename } | { kind: "CREATE"; value: PointsCreate } | { kind: "STATE"; value: PointsTypeChange };

export function PointsTypesTab({ canEdit }: { canEdit: boolean }) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selectedCode, setSelectedCode] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [nameError, setNameError] = useState("");
  const [editing, setEditing] = useState<"name" | "state" | "default" | null>(null);
  const selected = types.data?.find((item) => item.typeCode === selectedCode);
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation({
    mutationFn: (command: Command) => {
      switch (command.kind) {
        case "CREATE": return api.createPointsType(command.value);
        case "STATE": return api.transitionPointsType(command.value);
        case "RENAME": return api.renamePointsType(command.value);
      }
    },
    onSuccess: async (_result, command) => {
      intent.current = undefined;
      await qc.invalidateQueries({ queryKey: ["points-types"] });
      setEditing(null);
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

  function statusText(row: PointsTypeConfig) {
    return t(row.status === "DRAFT" ? "草稿" : row.status === "ACTIVE" ? (row.earningPaused ? "赠送已暂停" : "生效中") : row.status === "DRAINING" ? "清偿中" : "已退役", row.status === "DRAFT" ? "Draft" : row.status === "ACTIVE" ? (row.earningPaused ? "Earning paused" : "Active") : row.status === "DRAINING" ? "Draining" : "Retired");
  }
  function startEditing(field: "name" | "state" | "default") {
    if (save.isPending) return;
    save.reset(); setNameError(""); setEditing(field);
  }
  function cancelEditing() { save.reset(); setNameError(""); setEditing(null); }
  function editIcon(field: "name" | "state" | "default", label: string) {
    return canEdit && <Button type="button" variant="ghost" size="icon" aria-label={label} title={label} disabled={save.isPending} onClick={() => startEditing(field)}><Pencil className="size-3.5" aria-hidden="true" /></Button>;
  }
  const editButtons = <><Button size="sm" type="submit" loading={save.isPending}>{t("保存", "Save")}</Button><Button size="sm" variant="outline" type="button" disabled={save.isPending} onClick={cancelEditing}>{t("取消", "Cancel")}</Button></>;

  const columns = [
    { header: t("积分名称", "Points name"), cell: (row: PointsTypeConfig) => row.name },
    { header: t("积分代码", "Points code"), cell: (row: PointsTypeConfig) => <span dir="ltr">{row.typeCode}</span> },
    { header: t("货币类型", "Currency"), cell: (row: PointsTypeConfig) => { const c = pointsCurrency(row.currency); return c ? t(c.name, c.english) : row.currency; } },
    { header: t("货币代码", "Currency code"), cell: (row: PointsTypeConfig) => row.currency },
    { header: t("货币单位", "Currency unit"), cell: (row: PointsTypeConfig) => { const unit = pointsCurrency(row.currency)?.units[0]; return unit ? t(unit.name, unit.english) : t("最小货币单位", "Minor unit"); } },
    { header: t("货币单位数量", "Currency units"), numeric: true, cell: (row: PointsTypeConfig) => row.rate.minorUnit },
    { header: t("兑换积分数量", "Points amount"), numeric: true, cell: (row: PointsTypeConfig) => row.rate.pointsUnit },
    { header: t("抵扣上限", "Deduction cap"), numeric: true, cell: (row: PointsTypeConfig) => `${(row.deductRatioBp / 100).toFixed(2)}%` },
    { header: t("状态", "Status"), cell: statusText },
    { header: t("订单默认", "Order default"), cell: (row: PointsTypeConfig) => row.orderDefault ? t("是", "Yes") : t("否", "No") },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = types.error;

  return <div className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <SectionHeader title={t("积分设置", "Points settings")} summary={types.data?.length ?? 0} className="mb-0" />
      {canEdit && <Button onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button>}
    </div>
    <Notice>{t("多个积分类型可同时生效，各类型分别划拨、发放和核销。新类型生效后，现有订单默认积分保持不变。暂停只停止新增赠送，已有积分仍可抵扣。设为默认只影响后续订单。", "Multiple points types can be active, with separate allocations, grants and redemptions. Activating a new type preserves the existing order default. Pausing only stops new earning; existing points remain redeemable. Changing the default only affects future orders.")}</Notice>
    <DataTable columns={columns} rows={types.data} loading={types.isPending} error={types.error} onRetry={() => void types.refetch()}
      rowKey={(row) => row.typeCode} empty={t("还没有积分类型，请先新建。", "No points types yet. Create one to get started.")}
      emptyAction={canEdit ? <Button size="sm" onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button> : undefined}
      rowProps={(row) => ({ role: "button", tabIndex: 0, "aria-label": `${t("查看积分类型", "View points type")} ${row.name}`,
        className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
        onClick: () => { setSelectedCode(row.typeCode); setEditing(null); setNameError(""); save.reset(); },
        onKeyDown: (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedCode(row.typeCode); setEditing(null); setNameError(""); save.reset(); } },
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

    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) { setSelectedCode(""); setEditing(null); setNameError(""); save.reset(); } }}
      title={selected?.name ?? ""}
      desc={t("查看积分信息，点击编辑图标修改。", "View points details and use the edit icons to make changes.")}
      width="w-[760px]">
      {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void types.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      {save.isSuccess && <Notice>{t("已保存", "Saved")}</Notice>}
      {selected && <div className={card}>
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-6 gap-y-3 txt-body">
          <dt className="text-muted-foreground">{t("积分名称", "Points name")}</dt>
          <dd>{editing === "name" && canEdit ? <form id="points-type-rename" key={`${selected.typeCode}:${selected.name}`} className="flex flex-wrap items-center gap-2" onSubmit={(event) => {
            event.preventDefault();
            const name = String(new FormData(event.currentTarget).get("name") ?? "").trim();
            if (!name || name.length > 80) { setNameError(t("积分名称需为 1 至 80 个字符。", "Points name must contain 1 to 80 characters.")); return; }
            if (types.data?.some((row) => row.typeCode !== selected.typeCode && row.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
              setNameError(t("积分名称已存在，请修改后重试。", "This points name already exists. Change it and try again.")); return;
            }
            setNameError("");
            submit({ kind: "RENAME", value: { requestId: "", typeCode: selected.typeCode, expectedName: selected.name, name } });
          }}>
            <Input type="text" name="name" aria-label={t("积分名称", "Points name")} className="w-full sm:w-56" dir="auto" required maxLength={80} defaultValue={selected.name} disabled={save.isPending} autoFocus onChange={() => setNameError("")} />
            {editButtons}
            {nameError && <div className="w-full"><Notice tone="warning">{nameError}</Notice></div>}
          </form> : <div className="flex items-center gap-2"><span>{selected.name}</span>{editIcon("name", t("编辑积分名称", "Edit points name"))}</div>}</dd>
          <dt className="text-muted-foreground">{t("积分代码", "Points code")}</dt><dd dir="ltr">{selected.typeCode}</dd>
          <dt className="text-muted-foreground">{t("货币类型", "Currency")}</dt><dd>{(() => { const c = pointsCurrency(selected.currency); return c ? t(c.name, c.english) : selected.currency; })()}</dd>
          <dt className="text-muted-foreground">{t("货币代码", "Currency code")}</dt><dd>{selected.currency}</dd>
          <dt className="text-muted-foreground">{t("货币单位", "Currency unit")}</dt><dd>{(() => { const u = pointsCurrency(selected.currency)?.units[0]; return u ? t(u.name, u.english) : t("最小货币单位", "Minor unit"); })()}</dd>
          <dt className="text-muted-foreground">{t("货币单位数量", "Currency units")}</dt><dd>{selected.rate.minorUnit}</dd>
          <dt className="text-muted-foreground">{t("兑换积分数量", "Points amount")}</dt><dd>{selected.rate.pointsUnit}</dd>
          <dt className="text-muted-foreground">{t("抵扣上限", "Deduction cap")}</dt><dd>{(selected.deductRatioBp / 100).toFixed(2)}%</dd>
          <dt className="text-muted-foreground">{t("状态", "Status")}</dt>
          <dd>{editing === "state" && canEdit ? <form id="points-type-state" className="flex flex-wrap items-center gap-2" onSubmit={(event) => {
            event.preventDefault();
            const action = String(new FormData(event.currentTarget).get("action")) as PointsTypeChange["action"];
            submit({ kind: "STATE", value: { requestId: "", typeCode: selected.typeCode, action } });
          }}>
            <Select name="action" aria-label={t("状态操作", "Status action")} className="w-auto max-w-full" disabled={save.isPending}>
              {selected.status === "DRAFT" && <option value="ACTIVATE">{t("生效", "Activate")}</option>}
              {selected.status === "ACTIVE" && <>
                <option value={selected.earningPaused ? "RESUME_EARNING" : "PAUSE_EARNING"}>{selected.earningPaused ? t("恢复赠送", "Resume earning") : t("暂停赠送", "Pause earning")}</option>
                <option value="DRAIN">{t("清偿停用", "Drain")}</option>
              </>}
              {selected.status === "DRAINING" && <option value="RETIRE">{t("退役（须全部清偿）", "Retire (all obligations must be cleared)")}</option>}
            </Select>{editButtons}
          </form> : <div className="flex items-center gap-2"><span>{statusText(selected)}</span>{selected.status !== "RETIRED" && editIcon("state", t("编辑状态", "Edit status"))}</div>}</dd>
          <dt className="text-muted-foreground">{t("订单默认", "Order default")}</dt>
          <dd>{editing === "default" && canEdit ? <form id="points-type-default" className="flex flex-wrap items-center gap-2" onSubmit={(event) => {
            event.preventDefault(); submit({ kind: "STATE", value: { requestId: "", typeCode: selected.typeCode, action: "SET_DEFAULT" } });
          }}>
            <Select aria-label={t("订单默认", "Order default")} className="w-auto" disabled={save.isPending}><option>{t("设为订单默认", "Set as order default")}</option></Select>{editButtons}
          </form> : <div className="flex items-center gap-2"><span>{selected.orderDefault ? t("是", "Yes") : t("否", "No")}</span>{selected.status === "ACTIVE" && !selected.orderDefault && editIcon("default", t("编辑订单默认", "Edit order default"))}</div>}</dd>
        </dl>
      </div>}


    </Drawer>
  </div>;
}
