"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { OrderPointsIntegrationChange, OrderPointsIntegrationQ, PointsAgreementPage } from "@/lib/types";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import type { PointsAgreement } from "@/lib/types";

type AgreementRow = PointsAgreementPage["rows"][number];

export function PointsStoreAccessPage({ canEdit }: { canEdit: boolean }) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [storeNo, setStoreNo] = useState("");
  const [typeCode, setTypeCode] = useState("");
  const [after, setAfter] = useState(0);
  const [agreementRows, setAgreementRows] = useState<AgreementRow[]>([]);
  const [selected, setSelected] = useState<AgreementRow | null>(null);
  const [scene, setScene] = useState<OrderPointsIntegrationQ["scene"]>("ONLINE");
  const [detachBlocked, setDetachBlocked] = useState(false);
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const stores = useQuery({ queryKey: ["points-store-picker", keyword], queryFn: () => api.listStores({ keyword, page: 1, size: 50 }) });
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const agreements = useQuery({
    queryKey: ["points-agreements", storeNo, typeCode, after], queryFn: () => api.pointsAgreements({ storeNo, typeCode, after, limit: 100 }), enabled: !!storeNo && !!typeCode,
  });
  useEffect(() => {
    if (!agreements.data) return;
    setAgreementRows((current) => after === 0 ? agreements.data!.rows : [...current, ...agreements.data!.rows]);
  }, [agreements.data, after]);
  const integration = useQuery({ queryKey: ["points-integration", storeNo, scene], queryFn: () => api.orderPointsIntegration({ storeNo, scene }), enabled: !!storeNo });
  const obligations = useQuery({ queryKey: ["points-obligations", storeNo], queryFn: () => api.orderPointsObligations(storeNo), enabled: !!storeNo });
  const change = useMutation({
    mutationFn: (value: OrderPointsIntegrationChange) => api.changeOrderPointsIntegration(value),
    onSuccess: () => {
      intent.current = undefined;
      void qc.invalidateQueries({ queryKey: ["points-integration", storeNo] });
      void qc.invalidateQueries({ queryKey: ["points-obligations", storeNo] });
    },
  });
  const storeRows = stores.data?.records ?? [];
  const rows = agreementRows.filter((row) => !typeCode || row.agreement.typeCode === typeCode);
  const o = obligations.data;
  const obligationsClear = !!o?.known && o.sourceSettled && !!o.pay?.known && o.openBindings === 0 &&
    o.pay.openHolds === 0 && o.pay.pendingPromises === 0 && o.pay.openRefunds === 0 &&
    o.pay.openStatements === 0 && o.pay.unknownFunds === 0 && o.pay.pendingEvents === 0;
  const columns = [
    { header: t("积分类型", "Points type"), cell: (row: AgreementRow) => <strong>{row.agreement.typeCode}</strong> },
    { header: t("门店", "Store"), cell: (row: AgreementRow) => `${storeRows.find((s) => s.storeNo === row.agreement.storeNo)?.name ?? row.agreement.storeNo} · ${row.agreement.storeNo}` },
    { header: t("签署主体", "Signed by"), cell: (row: AgreementRow) => row.agreement.signedBy },
    { header: t("协议版本", "Terms version"), cell: (row: AgreementRow) => row.agreement.termsVersion },
    { header: t("结算方式", "Settlement"), cell: (row: AgreementRow) => row.agreement.settleMode },
    { header: t("状态", "Status"), cell: (row: AgreementRow) => row.state },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const title = selected ? `${selected.agreement.storeNo} · ${selected.agreement.typeCode}` : "";

  return <div className="space-y-4">
    <SectionHeader title={t("门店协议与接入", "Store agreements & access")} summary={rows.length} />
    <Notice>{t("先选择积分类型和门店查看其已签署协议。订单积分接入策略与义务核验在协议详情中查看；变更会由服务端按版本和未结义务再次校验。", "Choose a points type and store to review signed agreements. Order integration and obligation checks are available in agreement details; the server revalidates versions and outstanding obligations on changes.")}</Notice>
    <div className="grid gap-3 rounded-card border border-border p-4 md:grid-cols-[minmax(240px,1fr)_240px_auto] md:items-end">
      <label>{t("搜索门店", "Search stores")}<Input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder={t("门店号、名称或商户", "Store no., name or merchant")} /></label>
      <label>{t("积分类型", "Points type")}<Select value={typeCode} onChange={(event) => { setTypeCode(event.target.value); setAfter(0); setAgreementRows([]); setSelected(null); }}><option value="">{t("请选择类型", "Choose a type")}</option>{(types.data ?? []).map((item) => <option key={item.typeCode} value={item.typeCode}>{item.name} · {item.typeCode}</option>)}</Select></label>
      <label>{t("选择门店", "Select store")}<Select value={storeNo} onChange={(event) => { setStoreNo(event.target.value); setAfter(0); setAgreementRows([]); setSelected(null); }}><option value="">{t("请选择门店", "Choose a store")}</option>{storeRows.map((store) => <option key={store.storeNo} value={store.storeNo}>{store.name} · {store.storeNo}</option>)}</Select></label>
    </div>
    <DataTable columns={columns} rows={rows} loading={!!storeNo && agreements.isPending} error={stores.error || agreements.error} onRetry={() => { void stores.refetch(); void agreements.refetch(); }}
      rowKey={(row) => row.agreement.agreementNo} empty={!storeNo || !typeCode ? t("请选择积分类型和门店查看协议。", "Choose a points type and store to view agreements.") : t("该门店暂无协议记录。", "No agreements found for this store.")}
      rowProps={(row) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => { setSelected(row); change.reset(); }, onKeyDown: (event) => { if (event.key === "Enter") setSelected(row); } })} />
    {agreements.data?.hasMore && <Button variant="outline" onClick={() => setAfter(agreements.data!.next)}>{t("加载更多协议", "Load more agreements")}</Button>}
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) { setSelected(null); change.reset(); } }} title={title} desc={t("门店签署快照、订单积分接入与未结义务", "Signed agreement snapshot, order integration and outstanding obligations")} width="w-[760px]">
      {selected && <div className="space-y-5">
        <section className="space-y-2 rounded-card border border-border p-4">
          <h3 className="txt-label">{t("协议快照", "Agreement snapshot")}</h3>
          <p>{t("协议编号", "Agreement")}: {selected.agreement.agreementNo} · {t("签署主体", "Signed by")}: {selected.agreement.signedBy}</p>
          <p>{t("条款版本", "Terms version")}: {selected.agreement.termsVersion} · {t("路径", "Path")}: {selected.agreement.path} · {t("通道", "Channel")}: {selected.agreement.channel}</p>
          <p>{t("接收账户版本", "Account version")}: {selected.agreement.accountVersion} · {t("处理状态", "Processing state")}: {selected.state}</p>
          <details><summary className="cursor-pointer">{t("查看签署条款", "View accepted terms")}</summary><pre dir="auto" className="mt-2 whitespace-pre-wrap">{selected.agreement.termsText}</pre></details>
        </section>
        <section className="space-y-3 rounded-card border border-border p-4">
          <div className="flex flex-wrap items-end justify-between gap-3"><div><h3 className="txt-label">{t("订单积分接入", "Order points integration")}</h3><p className="txt-caption text-muted-foreground">{t("状态版本", "Policy version")}: {integration.data?.version ?? "—"}</p></div><label>{t("交易场景", "Scene")}<Select value={scene} onChange={(event) => setScene(event.target.value as OrderPointsIntegrationQ["scene"])}><option value="ONLINE">{t("线上", "Online")}</option><option value="OFFLINE">{t("线下", "Offline")}</option></Select></label></div>
          {integration.isError && <Notice tone="warning">{t("接入策略读取失败", "Could not load integration policy")}</Notice>}
          <p>{t("模式", "Mode")}: {integration.data?.mode ?? "—"} · {t("返分", "Earn")}: {integration.data?.earnEnabled ? t("开启", "On") : t("关闭", "Off")} · {t("抵扣", "Redeem")}: {integration.data?.redeemEnabled ? t("开启", "On") : t("关闭", "Off")}</p>
          <div className="grid gap-2 txt-body md:grid-cols-2"><span>{t("义务核验", "Obligation check")}: {obligations.data?.known ? t("完整", "Known") : t("未知/未完成", "Unknown/incomplete")}</span><span>{t("未结订单绑定", "Open bindings")}: {obligations.data?.openBindings ?? "—"}</span><span>{t("待完成承诺", "Pending promises")}: {obligations.data?.pay?.pendingPromises ?? "—"}</span><span>{t("未完成退款", "Open refunds")}: {obligations.data?.pay?.openRefunds ?? "—"}</span></div>
          {canEdit && integration.data && <form className="grid gap-3 border-t border-border pt-3 md:grid-cols-2" onSubmit={(event) => { event.preventDefault(); const form = new FormData(event.currentTarget); const draft = { storeNo: selected.agreement.storeNo, scene, mode: String(form.get("mode")) as OrderPointsIntegrationChange["mode"], earnEnabled: form.get("earn") === "on", redeemEnabled: form.get("redeem") === "on", expectedVersion: integration.data!.version, reason: String(form.get("reason")).trim() }; const next = pointsCommand(draft, intent.current); intent.current = next.intent; setDetachBlocked(next.command.mode === "DETACHED" && !obligationsClear); if (next.command.mode === "DETACHED" && !obligationsClear) return; if (next.command.mode === "DETACHED" || next.command.mode === "DRAINING") { void confirm({ title: t("确认变更积分接入？", "Confirm points integration change?"), desc: t("此操作会改变该门店此交易场景后续订单的积分处理。服务端还会再次校验未结义务。", "This changes points processing for future orders in this store and scene. The server will recheck outstanding obligations."), danger: next.command.mode === "DETACHED", confirmText: t("确认变更", "Confirm change"), action: () => change.mutateAsync(next.command) }); } else change.mutate(next.command); }}>
            <label>{t("接入模式", "Integration mode")}<Select name="mode" defaultValue={integration.data.mode}><option value="DETACHED">DETACHED</option><option value="ACTIVE">ACTIVE</option><option value="DRAINING">DRAINING</option></Select></label>
            <div className="flex gap-4"><label><input className="focus-ring" type="checkbox" name="earn" defaultChecked={integration.data.earnEnabled} /> {t("允许返分", "Allow earning")}</label><label><input className="focus-ring" type="checkbox" name="redeem" defaultChecked={integration.data.redeemEnabled} /> {t("允许抵扣", "Allow redemption")}</label></div>
            <label className="md:col-span-2">{t("变更原因", "Reason")}<Input name="reason" required maxLength={500} /></label>
            {detachBlocked && <Notice tone="warning">{t("义务核验未知或仍有未结事项，暂不能完全断开接入。", "Obligations are unknown or outstanding; full detachment is blocked.")}</Notice>}
            {change.isError && <Notice tone="warning">{change.error.message}</Notice>}{change.isSuccess && <Notice>{t("接入策略已更新", "Integration policy updated")}</Notice>}
            <div className="md:col-span-2"><Button disabled={change.isPending} type="submit">{t("保存接入策略", "Save integration")}</Button></div>
          </form>}
          {!canEdit && <p className="txt-caption text-muted-foreground">{t("当前为只读权限。", "Read-only access.")}</p>}
        </section>
      </div>}
    </Drawer>
    {dialog}
  </div>;
}
