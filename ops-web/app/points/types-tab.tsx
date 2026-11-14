"use client";

import { useRef, useState, type ReactNode } from "react";
import { Pencil, Search } from "lucide-react";
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
import styles from "./types-tab.module.css";
import { Badge } from "@/components/ui/badge";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";

type Command = { kind: "RENAME"; value: PointsTypeRename } | { kind: "CREATE"; value: PointsCreate } | { kind: "STATE"; value: PointsTypeChange };

export function PointsTypesTab({ canEdit }: { canEdit: boolean }) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [search, setSearch] = useState("");
  const [showHelp, setShowHelp] = useState(false);
  const [savedField, setSavedField] = useState<string | null>(null);
  const [selectedCode, setSelectedCode] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [nameError, setNameError] = useState("");
  const [editing, setEditing] = useState<"name" | "state" | "default" | null>(null);
  const selected = types.data?.find((item) => item.typeCode === selectedCode);
  const editTriggers = useRef<Partial<Record<"name" | "state" | "default", HTMLButtonElement | null>>>({});
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
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["points-types"] }),
        qc.invalidateQueries({ queryKey: ["points-earning-context"] }),
      ]);
      setSavedField(command.kind === "RENAME" ? "name" : command.kind === "STATE" ? (command.value.action === "SET_DEFAULT" ? "default" : "state") : null);
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
    save.reset(); setSavedField(null); setNameError(""); setEditing(field);
  }
  function cancelEditing() {
    const field = editing;
    save.reset(); setNameError(""); setEditing(null);
    if (field) requestAnimationFrame(() => editTriggers.current[field]?.focus());
  }
  function editIcon(field: "name" | "state" | "default", label: string) {
    return canEdit && <Button type="button" variant="ghost" size="icon" ref={(node) => { editTriggers.current[field] = node; }} className={styles.editIcon} aria-label={label} title={label} disabled={save.isPending} onClick={() => startEditing(field)}><Pencil className="size-3.5" aria-hidden="true" /></Button>;
  }
  const editButtons = <><Button size="sm" type="submit" loading={save.isPending}>{t("保存", "Save")}</Button><Button size="sm" variant="outline" type="button" disabled={save.isPending} onClick={cancelEditing}>{t("取消", "Cancel")}</Button></>;

  function statusBadge(row: PointsTypeConfig) {
    return <Badge tone={row.status === "ACTIVE" ? (row.earningPaused ? "warning" : "success") : "muted"}>{statusText(row)}</Badge>;
  }
  function feedback(field: string) {
    return <>{editing === field && save.isError && !(field === "name" && nameError) && <p className={styles.fieldError} id={field === "name" ? "points-name-error" : undefined} role="alert">{save.error.message}</p>}{savedField === field && !editing && <span className={styles.saved} role="status">{t("已更新", "Updated")}</span>}</>;
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
    { header: t("状态", "Status"), cell: statusBadge },
    { header: t("订单默认", "Order default"), cell: (row: PointsTypeConfig) => row.orderDefault ? t("是", "Yes") : t("否", "No") },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const filteredTypes = types.data?.filter((row) => `${row.name} ${row.typeCode}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  const error = types.error;

  return <div className={`space-y-3 ${styles.page}`}>
    <div className="flex flex-wrap items-center justify-between gap-3">
      <SectionHeader title={t("积分设置", "Points settings")} summary={types.data?.length ?? 0} className="mb-0" />
      {canEdit && <Button onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button>}
    </div>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div className={styles.search}><Search className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" /><Input type="search" value={search} onChange={(event) => setSearch(event.target.value)} aria-label={t("搜索积分名称或代码", "Search points name or code")} placeholder={t("搜索积分名称或代码", "Search points name or code")} /></div>
      <Button size="sm" variant="ghost" aria-expanded={showHelp} aria-controls="points-types-help" onClick={() => setShowHelp(!showHelp)}>{t("使用说明", "Help")}</Button>
    </div>
    {showHelp && <div id="points-types-help"><Notice>{t("多个积分类型可同时生效，各类型分别划拨、发放和核销。新类型生效后，现有订单默认积分保持不变。暂停只停止新增赠送，已有积分仍可抵扣。设为默认只影响后续订单。", "Multiple points types can be active, with separate allocations, grants and redemptions. Activating a new type preserves the existing order default. Pausing only stops new earning; existing points remain redeemable. Changing the default only affects future orders.")}</Notice></div>}
    <DataTable columns={columns} rows={filteredTypes} loading={types.isPending} error={types.error} onRetry={() => void types.refetch()}
      rowKey={(row) => row.typeCode} empty={search.trim() ? t("没有匹配的积分类型。", "No matching points types.") : t("还没有积分类型，请先新建。", "No points types yet. Create one to get started.")}
      emptyAction={canEdit && !search.trim() ? <Button size="sm" onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button> : undefined}
      rowProps={(row) => ({ role: "button", tabIndex: 0, "aria-label": `${t("查看积分类型", "View points type")} ${row.name}`,
        className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
        onClick: () => { setSelectedCode(row.typeCode); setSavedField(null); setEditing(null); setNameError(""); save.reset(); },
        onKeyDown: (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelectedCode(row.typeCode); setSavedField(null); setEditing(null); setNameError(""); save.reset(); } },
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
      desc={t("积分详情", "Points details")}
      width="w-[560px]" className={styles.drawer} headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}
      onEscapeKeyDown={(event) => { if (editing) { event.preventDefault(); if (!save.isPending) cancelEditing(); } }}>
      {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void types.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}

      {selected && <div className={styles.surface}>
        <section className={styles.section}><h3>{t("基本信息", "Basic information")}</h3><dl className={styles.fields}>
        <DetailField label={t("积分名称", "Points name")}>{editing === "name" && canEdit ? <form id="points-type-rename" key={`${selected.typeCode}:${selected.name}`} className={styles.inlineForm} onSubmit={(event) => {
            event.preventDefault();
            const name = String(new FormData(event.currentTarget).get("name") ?? "").trim();
            if (!name || name.length > 80) { setNameError(t("积分名称需为 1 至 80 个字符。", "Points name must contain 1 to 80 characters.")); return; }
            if (types.data?.some((row) => row.typeCode !== selected.typeCode && row.name.toLocaleLowerCase() === name.toLocaleLowerCase())) {
              setNameError(t("积分名称已存在，请修改后重试。", "This points name already exists. Change it and try again.")); return;
            }
            setNameError("");
            submit({ kind: "RENAME", value: { requestId: "", typeCode: selected.typeCode, expectedName: selected.name, name } });
          }}>
            <Input type="text" name="name" aria-label={t("积分名称", "Points name")} className={styles.nameInput} dir="auto" required maxLength={80} defaultValue={selected.name} disabled={save.isPending} aria-invalid={!!nameError || save.isError} aria-describedby="points-name-error" autoFocus onChange={() => setNameError("")} />
            {editButtons}
            {nameError && <p id="points-name-error" className={styles.fieldError} role="alert">{nameError}</p>}
          {feedback("name")}</form> : <div className="flex items-center gap-2"><span>{selected.name}</span>{editIcon("name", t("编辑积分名称", "Edit points name"))}{feedback("name")}</div>}</DetailField>
        <DetailField label={t("积分代码", "Points code")}>{selected.typeCode}</DetailField>
        </dl></section>
        <section className={styles.section}><h3>{t("使用设置", "Usage settings")}</h3><dl className={styles.fields}>
        <DetailField label={t("状态", "Status")}>{editing === "state" && canEdit ? <form id="points-type-state" className={styles.inlineForm} onSubmit={(event) => {
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
          <p className={styles.editorNote}>{t("暂停只停止新增赠送，已有积分仍可抵扣。", "Pausing only stops new earning; existing points remain redeemable.")}</p>{feedback("state")}</form> : <div className="flex items-center gap-2">{statusBadge(selected)}{selected.status !== "RETIRED" && editIcon("state", t("编辑状态", "Edit status"))}{feedback("state")}</div>}</DetailField>
        <DetailField label={t("订单默认", "Order default")}>{editing === "default" && canEdit ? <form id="points-type-default" className={styles.inlineForm} onSubmit={(event) => {
            event.preventDefault(); submit({ kind: "STATE", value: { requestId: "", typeCode: selected.typeCode, action: "SET_DEFAULT" } });
          }}>
            <Select aria-label={t("订单默认", "Order default")} className="w-auto" disabled={save.isPending}><option>{t("设为订单默认", "Set as order default")}</option></Select>{editButtons}
          <p className={styles.editorNote}>{t("仅影响后续新订单。", "Only affects future orders.")}</p>{feedback("default")}</form> : <div className="flex items-center gap-2"><span>{selected.orderDefault ? t("是", "Yes") : t("否", "No")}</span>{selected.status === "ACTIVE" && !selected.orderDefault && editIcon("default", t("编辑订单默认", "Edit order default"))}{feedback("default")}</div>}</DetailField>
        </dl></section>
        <section className={styles.section}><h3>{t("兑换配置", "Conversion settings")}</h3><dl className={styles.fields}>
        <DetailField label={t("货币类型", "Currency")}>{(() => { const c = pointsCurrency(selected.currency); return c ? t(c.name, c.english) : selected.currency; })()}</DetailField>
        <DetailField label={t("货币代码", "Currency code")}>{selected.currency}</DetailField>
        <DetailField label={t("货币单位", "Currency unit")}>{(() => { const u = pointsCurrency(selected.currency)?.units[0]; return u ? t(u.name, u.english) : t("最小货币单位", "Minor unit"); })()}</DetailField>
        <DetailField label={t("货币单位数量", "Currency units")}>{selected.rate.minorUnit}</DetailField>
        <DetailField label={t("兑换积分数量", "Points amount")}>{selected.rate.pointsUnit}</DetailField>
        <DetailField label={t("抵扣上限", "Deduction cap")}>{(selected.deductRatioBp / 100).toFixed(2)}%</DetailField>
        </dl></section>
      </div>}


    </Drawer>
  </div>;
}

function DetailField({ label, children }: { label: string; children: ReactNode }) {
  return <div className={styles.field}><dt>{label}</dt><dd>{children}</dd></div>;
}
