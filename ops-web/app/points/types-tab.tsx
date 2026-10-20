"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { OrderPointsIntegrationChange, PointsCreate, PointsFallbackChange, PointsTermsChange, PointsTypeChange, PointsTypeConfig } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { ManualPointsRefunds } from "./manual-refunds";

type Command = { kind: "CREATE"; value: PointsCreate } | { kind: "STATE"; value: PointsTypeChange } |
  { kind: "RULE"; value: PointsFallbackChange } | { kind: "TERMS"; value: PointsTermsChange };
export function PointsConfigurationTab({ canEdit }: { canEdit: boolean }) {
  const { locale, t } = usePointsText();
  const en = locale !== "zh";
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [code, select] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const type = types.data?.find((x) => x.typeCode === code);
  const rule = useQuery({ queryKey: ["points-fallback", code], queryFn: () => api.pointsFallback(code), enabled: !!code });
  const terms = useQuery({ queryKey: ["points-terms", code], queryFn: () => api.pointsTerms(code), enabled: !!code });
  const [storeInput, setStoreInput] = useState("");
  const [store, setStore] = useState("");
  const [after, setAfter] = useState(0);
  const [integrationInput, setIntegrationInput] = useState("");
  const [integrationStore, setIntegrationStore] = useState("");
  const [integrationScene, setIntegrationScene] = useState<"ONLINE" | "OFFLINE">("ONLINE");
  const [inspectionInput, setInspectionInput] = useState("");
  const [inspectionSub, setInspectionSub] = useState("");
  const inspection = useQuery({ queryKey: ["points-order-inspection", inspectionSub],
    queryFn: () => api.inspectOrderPoints(inspectionSub), enabled: !!inspectionSub });
  const integration = useQuery({ queryKey: ["points-order-integration", integrationStore, integrationScene],
    queryFn: () => api.orderPointsIntegration({ storeNo: integrationStore, scene: integrationScene }),
    enabled: !!integrationStore });
  const obligations = useQuery({ queryKey: ["points-store-obligations", integrationStore],
    queryFn: () => api.orderPointsObligations(integrationStore), enabled: !!integrationStore });
  const integrationIntent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const integrationSave = useMutation({ mutationFn: (draft: OrderPointsIntegrationChange) => {
    const next = pointsCommand({ ...draft, requestId: "" }, integrationIntent.current);
    integrationIntent.current = next.intent;
    return api.changeOrderPointsIntegration({ ...draft, requestId: next.command.requestId });
  }, onSuccess: () => { integrationIntent.current = undefined; void qc.invalidateQueries({ queryKey: ["points-store-obligations", integrationStore] }); void qc.invalidateQueries({ queryKey: ["points-order-integration", integrationStore, integrationScene] }); } });
  const agreements = useQuery({ queryKey: ["points-agreements", code, store, after], queryFn: () => api.pointsAgreements({ typeCode: code, storeNo: store, after, limit: 50 }), enabled: !!code && !!store });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation({ mutationFn: (command: Command) => {
    switch (command.kind) {
      case "CREATE": return api.createPointsType(command.value);
      case "STATE": return api.transitionPointsType(command.value);
      case "RULE": return api.savePointsFallback(command.value);
      case "TERMS": return api.publishPointsTerms(command.value);
    }
  }, onSuccess: (_result, command) => {
    intent.current = undefined;
    void qc.invalidateQueries({ queryKey: ["points-types"] });
    void qc.invalidateQueries({ queryKey: ["points-fallback"] });
    void qc.invalidateQueries({ queryKey: ["points-terms"] });
    if (command.kind === "CREATE") {
      setCreateOpen(false);
      select(command.value.command.typeCode);
    }
  } });
  function send(command: Command) {
    const next = pointsCommand({ ...command, value: { ...command.value, requestId: "" } }, intent.current);
    intent.current = next.intent;
    save.mutate({ ...command, value: { ...command.value, requestId: next.command.requestId } } as Command);
  }
  const error = types.error || rule.error || terms.error || agreements.error;
  const integrationError = integration.error || integrationSave.error;
  const card = "space-y-3 rounded-card border border-border p-4";
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
  return <div className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <SectionHeader title={t("积分类型、规则与门店协议", "Points types, rules and store agreements")} summary={types.data?.length ?? 0} className="mb-0" />
      {canEdit && <Button onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button>}
    </div>
    <Notice>{t("金额使用类型币种的最小货币单位。配置只影响后续交易，旧交易保留原比例、规则与协议快照。", "Amounts use the type's minor currency unit. Configuration affects future transactions; existing transactions retain their rate, rules and agreement.")}</Notice>
    <DataTable columns={columns} rows={types.data} loading={types.isPending} error={types.error} onRetry={() => void types.refetch()}
      rowKey={(row) => row.typeCode} empty={t("还没有积分类型，请先新建。", "No points types yet. Create one to get started.")}
      emptyAction={canEdit ? <Button size="sm" onClick={() => { save.reset(); setCreateOpen(true); }}>{t("新建积分类型", "New points type")}</Button> : undefined}
      rowProps={(row) => ({ role: "button", tabIndex: 0, "aria-label": `${t("查看积分类型", "View points type")} ${row.typeCode}`,
        className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary",
        onClick: () => { select(row.typeCode); setAfter(0); save.reset(); },
        onKeyDown: (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); select(row.typeCode); setAfter(0); save.reset(); } },
      })} />
    <Drawer open={createOpen} onOpenChange={(open) => { setCreateOpen(open); if (!open) save.reset(); }}
      title={t("新建积分类型（草稿）", "Create points type (draft)")}
      desc={t("设置积分类型、兑换比例与使用边界。创建后还需在详情中激活。", "Set the points type, conversion rate and usage limits. Activate it from details after creation.")}
      width="w-[640px]"
      footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{t("取消", "Cancel")}</Button><Button type="submit" form="points-type-create" disabled={save.isPending}>{t("新建草稿", "Create draft")}</Button></>}>
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      <form id="points-type-create" className="space-y-4" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); send({ kind: "CREATE", value: { requestId: "", command: { typeCode: String(d.get("code")).trim(), market: String(d.get("market")).trim(), rate: { pointsUnit: Number(d.get("points")), minorUnit: Number(d.get("minor")) }, deductRatioBp: Number(d.get("ratio")), idleDays: Number(d.get("days")) }, reason: String(d.get("reason")) } }); }}>
        <div className="grid gap-3 md:grid-cols-2">{[["code",t("类型代码", "Type code")], ["market",t("区域代码", "Market code")]].map(([name,label]) => <label key={name}>{label}<Input dir="auto" name={name} required maxLength={40} /></label>)}
          {[["points",t("比例：积分单位", "Rate: points unit"),1,Number.MAX_SAFE_INTEGER], ["minor",t("比例：最小货币单位", "Rate: minor currency unit"),1,Number.MAX_SAFE_INTEGER], ["ratio",t("抵扣上限（万分比）", "Deduction cap (bp)"),0,10000], ["days",t("闲置天数", "Idle days"),1,36500]].map(([name,label,min,max]) => <label key={String(name)}>{label}<Input dir="auto" name={String(name)} type="number" required min={Number(min)} max={Number(max)} step={1} /></label>)}
          <label className="md:col-span-2">{t("原因", "Reason")}<Input dir="auto" name="reason" required /></label>
        </div>
      </form>
    </Drawer>
    <Drawer open={!!type} onOpenChange={(open) => { if (!open) { select(""); setAfter(0); save.reset(); } }}
      title={type ? `${type.typeCode} · ${type.market} · ${type.status}` : ""}
      desc={t("维护类型状态、平台规则、协议和门店接入。", "Manage type state, platform rules, terms and store integration.")}
      width="w-[760px]">
      {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { if (type) { void rule.refetch(); void terms.refetch(); } if (store) void agreements.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
      {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
      {save.isSuccess && <Notice>{t("已保存，操作号：", "Saved, request ID: ")}{save.data.requestId}</Notice>}
    {type && <div className={card}><p dir="auto">{type.currency} / {type.scale} · {type.rate.pointsUnit} {t("积分", "points")} = {type.rate.minorUnit} {t("最小货币单位", "minor currency units")} · {t("抵扣上限（万分比）", "Deduction cap (bp)")} {type.deductRatioBp} · {t("闲置天数", "Idle days")} {type.idleDays}</p>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); send({ kind: "STATE", value: { requestId: "", typeCode: code, action: String(d.get("action")) as PointsTypeChange["action"], reason: String(d.get("reason")) } }); }}>
        <label>{t("状态流转", "State transition")} <Select name="action" disabled={!canEdit || type.status === "RETIRED"}>
          {type.status === "DRAFT" && <option value="ACTIVATE">{t("生效", "Activate")}</option>}{type.status === "ACTIVE" && <option value="DRAIN">{t("清偿停用", "Drain")}</option>}{type.status === "DRAINING" && <option value="RETIRE">{t("退役（须全部清偿）", "Retire (all obligations must be cleared)")}</option>}
        </Select></label><label>{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
        {canEdit && type.status !== "RETIRED" && <Button disabled={save.isPending} type="submit">{t("提交状态变更", "Change state")}</Button>}
      </form>
    </div>}
    {rule.data && <form key={`fallback:${code}:${rule.data.revision}`} className={card} onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); send({ kind: "RULE", value: { requestId: "", typeCode: code, expectedRevision: rule.data.revision, mode: String(d.get("mode")) as PointsFallbackChange["mode"], value: Number(d.get("value")), reason: String(d.get("reason")) } }); }}>
      <h3 className="txt-heading">{t("平台兜底规则 · 修订", "Platform fallback rule · revision")} {rule.data.revision}</h3>
      <p dir="auto">{t("商品例外和类目规则在商品管理维护；此处设置最终兜底。RATIO 使用万分比，FIXED 使用积分数量。", "Maintain product exceptions and category rules in Products. This is the final fallback. RATIO uses basis points; FIXED uses points.")}</p>
      <div className="flex flex-wrap gap-3"><label>{t("方式", "Mode")}<Select name="mode" defaultValue={rule.data.mode} disabled={!canEdit}><option>RATIO</option><option>FIXED</option></Select></label><label>{t("数值", "Value")}<Input dir="auto" name="value" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} required defaultValue={rule.data.value} disabled={!canEdit} /></label><label>{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label></div>
      {canEdit && <Button type="submit" disabled={save.isPending}>{t("保存新修订", "Save new revision")}</Button>}
    </form>}
    {code && !terms.isPending && !terms.isError && <form key={`terms:${code}:${terms.data?.version ?? "none"}`} className={card} onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); send({ kind: "TERMS", value: { requestId: "", typeCode: code, version: String(d.get("version")), text: String(d.get("text")), reason: String(d.get("reason")) } }); }}>
      <h3 className="txt-heading">{t("受理协议文本", "Acceptance terms")}</h3><p dir="auto">{t("发布文本不代表门店已签署。签署及解除由商家主账号办理。", "Publishing terms does not sign for a store. Signing and termination require the merchant owner.")}</p>
      <label className="block">{t("版本", "Version")}<Input dir="auto" name="version" required defaultValue={terms.data?.version ?? ""} disabled={!canEdit} /></label>
      <label className="block">{t("正文", "Text")}<textarea dir="auto" name="text" className="focus-ring min-h-40 w-full rounded-field border border-border p-3" required defaultValue={terms.data?.text ?? ""} disabled={!canEdit} /></label>
      <p dir="auto" className="break-all txt-caption">{terms.data?.hash}</p><label className="block">{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
      {canEdit && <Button disabled={save.isPending} type="submit">{t("发布版本", "Publish version")}</Button>}
    </form>}
    <div className={card}><h3 className="txt-heading">{t("门店协议查询", "Store agreement lookup")}</h3>
      <form className="flex items-end gap-3" onSubmit={(e) => { e.preventDefault(); setStore(storeInput.trim()); setAfter(0); }}><label>{t("门店编号", "Store ID")}<Input dir="auto" required value={storeInput} onChange={(e) => setStoreInput(e.target.value)} /></label><Button type="submit" disabled={!code}>{t("查询", "Search")}</Button></form>
      {agreements.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {agreements.data?.rows.map((r) => <div key={r.id} className="border-t border-border py-2"><p dir="auto">{r.agreement.storeNo} · {r.agreement.agreementNo} · {r.state} · v{r.version}</p><p dir="auto">{t("签署人 / 条款版本 / 结算方式", "Signer / terms version / settlement mode")}: {r.agreement.signedBy} / {r.agreement.termsVersion} / {r.agreement.settleMode}</p></div>)}
      {agreements.data?.rows.length === 0 && <p dir="auto">{t("此门店无协议记录。", "No agreement records for this store.")}</p>}
      {agreements.data && <div className="flex gap-2"><Button disabled={!after} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button disabled={!agreements.data.hasMore || agreements.isFetching} onClick={() => setAfter(agreements.data.next)}>{t("下一页", "Next page")}</Button></div>}
    </div>
    <div className={card}>
      <h3 className="txt-heading">{t("订单积分按门店接线", "Order points by store")}</h3>
      <p dir="auto">{t("默认脱离。停新增保留旧单清偿；完全脱离须服务端确认没有存量义务。", "Detached by default. Draining stops new orders while existing obligations finish; full detachment requires server proof of clearance.")}</p>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const next=integrationInput.trim(); if(next===integrationStore) { void integration.refetch(); void obligations.refetch(); } else setIntegrationStore(next); }}>
        <label>{t("门店编号", "Store ID")}<Input dir="auto" required value={integrationInput} onChange={(e) => setIntegrationInput(e.target.value)} /></label>
        <label>{t("场景", "Scene")}<Select value={integrationScene} onChange={(e) => setIntegrationScene(e.target.value as "ONLINE" | "OFFLINE")}><option value="ONLINE">ONLINE</option><option value="OFFLINE">OFFLINE</option></Select></label>
        <Button type="submit">{t("查询", "Search")}</Button>
      </form>
      {integration.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {integrationStore && <div className="space-y-1 border-t border-border py-2" dir="auto">
        <h4>{t("门店存量义务", "Existing store obligations")}</h4>
        {obligations.isFetching && <p>{t("加载中…", "Loading…")}</p>}
        {obligations.data?.known && <>
          <p>{t("启用场景 / 未关闭绑定", "Active scenes / open bindings")}: {obligations.data.activePolicies} / {obligations.data.openBindings}</p>
          <p>{t("源事件全部确认", "Source events acknowledged")}: {obligations.data.sourceSettled ? t("是", "Yes") : t("否", "No")}</p>
          <p>{t("占用 / 待兑现承诺 / 退款", "Holds / pending promises / refunds")}: {obligations.data.pay.openHolds} / {obligations.data.pay.pendingPromises} / {obligations.data.pay.openRefunds}</p>
          <p>{t("未结算单 / 未知资金 / 待处理事件", "Open statements / unknown funds / pending events")}: {obligations.data.pay.openStatements} / {obligations.data.pay.unknownFunds} / {obligations.data.pay.pendingEvents}</p>
        </>}
        {(obligations.error || obligations.data && !obligations.data.known) && <p role="alert">{t("义务核验不可用，不能据此判定可完全脱离。", "Obligation inspection unavailable; full detachment cannot be inferred.")}</p>}
        <p>{t("此处为查询快照，完全脱离仍由服务端重新核验。", "This is a read snapshot; the server rechecks before full detachment.")}</p>
      </div>}
      {integration.data && <form key={`${integrationStore}:${integrationScene}:${integration.data.version}`} className="space-y-3" onSubmit={(e) => {
        e.preventDefault(); const d = new FormData(e.currentTarget);
        integrationSave.mutate({ requestId: "", storeNo: integrationStore, scene: integrationScene,
          mode: String(d.get("mode")) as OrderPointsIntegrationChange["mode"],
          earnEnabled: d.get("earnEnabled") === "on", redeemEnabled: d.get("redeemEnabled") === "on",
          expectedVersion: integration.data.version, reason: String(d.get("reason")) });
      }}>
        <p dir="auto">{integration.data.mode} · v{integration.data.version} · {integration.data.actor ?? "—"} · {integration.data.reason ?? "—"}</p>
        <div className="flex flex-wrap gap-3">
          <label>{t("模式", "Mode")}<Select name="mode" defaultValue={integration.data.mode} disabled={!canEdit}><option>DETACHED</option><option>ACTIVE</option><option>DRAINING</option></Select></label>
          <label><input className="focus-ring" type="checkbox" name="earnEnabled" defaultChecked={integration.data.earnEnabled} disabled={!canEdit} /> {t("返分", "Earning")}</label>
          <label><input className="focus-ring" type="checkbox" name="redeemEnabled" defaultChecked={integration.data.redeemEnabled} disabled={!canEdit} /> {t("抵扣", "Redemption")}</label>
          <label>{t("原因", "Reason")}<Input dir="auto" name="reason" required disabled={!canEdit} /></label>
        </div>
        {canEdit && <Button type="submit" disabled={integrationSave.isPending}>{t("提交版本化变更", "Save versioned change")}</Button>}
      </form>}
      {integrationError && <p role="alert" className="text-destructive">{integrationError instanceof Error ? integrationError.message : String(integrationError)}</p>}
    </div>
    <div className={card}>
      <h3 className="txt-heading">{t("原订单积分核验", "Original order points inspection")}</h3>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => {
        e.preventDefault(); const sub = inspectionInput.trim();
        if (sub === inspectionSub) void inspection.refetch(); else setInspectionSub(sub);
      }}>
        <label>{t("子单号", "Sub-order ID")}<Input dir="auto" required value={inspectionInput} onChange={(e) => setInspectionInput(e.target.value)} /></label>
        <Button type="submit">{t("查询", "Search")}</Button>
      </form>
      {inspection.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {inspection.data && <div className="space-y-1" dir="auto">
        <p>{t("源绑定 / 下单阶段", "Source binding / placement")}: {inspection.data.bindingPhase ?? "—"} v{inspection.data.bindingVersion} / {inspection.data.placementPhase ?? "—"} v{inspection.data.placementVersion}</p>
        <p>{t("原占用号", "Original hold ID")}: {inspection.data.originalHoldNo ?? "—"}</p>
        <p>{t("退款阶段", "Refund phases")}: {inspection.data.refundPhases.join(", ") || "—"}</p>
        <p>{t("待投递源事件", "Pending source events")}: {inspection.data.pendingSourceEvents}</p>
        <p>{t("支付已核验", "Payment verified")}: {inspection.data.paymentVerified ? t("是", "Yes") : t("否", "No")}</p>
        <p>{t("差异代码", "Difference codes")}: {inspection.data.differences.join(", ") || "—"}</p>
      </div>}
      {inspection.error && <p role="alert" className="text-destructive">{inspection.error instanceof Error ? inspection.error.message : String(inspection.error)}</p>}
    </div>
    <ManualPointsRefunds key={integrationStore} storeNo={integrationStore} canEdit={canEdit} />
    </Drawer>
  </div>;
}
