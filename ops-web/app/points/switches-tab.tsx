"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { pointsCommand } from "@/lib/points-command";
import { api } from "@/lib/api";
import type { PointsScope, PointsFlow, PointsSwitchChange } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { usePointsText } from "@/lib/points-i18n";
import type { FinanceCopy } from "../finance/copy";

const CLIENTS = ["MP_WECHAT", "MP_ALIPAY", "IOS", "ANDROID", "H5"];
const SCOPES: PointsScope[] = ["GLOBAL", "REGION", "TYPE", "SCENE", "CLIENT", "MERCHANT", "STORE", "USER"];
export function PointsPolicyTab({ c, canEdit }: { c: FinanceCopy; canEdit: boolean }) {
  const { locale, t: text } = usePointsText();
  const en = locale !== "zh";
  const qc = useQueryClient();
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selected, setSelected] = useState("");
  const typeCode = selected || types.data?.[0]?.typeCode || "";
  const [scope, setScope] = useState<PointsScope>("STORE");
  const [subject, setSubject] = useState("");
  const [flow, setFlow] = useState<PointsFlow>("BOTH");
  const [enabled, setEnabled] = useState(false);
  const [reason, setReason] = useState("");
  const [after, setAfter] = useState(0);
  const list = useQuery({ queryKey: ["points-switches", typeCode, after], enabled: !!typeCode,
    queryFn: () => api.pointsSwitches({ typeCode, after, limit: 50 }) });
  const save = useMutation({ mutationFn: (v: PointsSwitchChange) => api.changePointsSwitch(v),
    onSuccess: () => { intent.current = undefined; setAfter(0); void qc.invalidateQueries({ queryKey: ["points-switches"] }); } });
  const global = scope === "GLOBAL" || scope === "REGION";
  const subjects = scope === "CLIENT" ? CLIENTS : ["ONLINE", "OFFLINE"];
  const effectiveSubject = scope === "GLOBAL" ? "ALL" : scope === "TYPE" ? typeCode : (scope === "CLIENT" || scope === "SCENE") ? subject || subjects[0] : subject.trim();

  return <div className="space-y-4">
    <SectionHeader title={text("积分范围开关", "Points availability switches")} />
    <Notice>{text("开关控制后续发放和核销。已受理交易继续按原快照结算；关闭不会修改账户余额。", "Switches control future earning and redemption. Accepted transactions retain their snapshots; balances are preserved.")}</Notice>
    {types.isError && <Notice tone="warning">{types.error.message} <Button onClick={() => void types.refetch()}>{text("重试", "Retry")}</Button></Notice>}
    {types.isPending && <p dir="auto">{c.ppLoading}</p>}
    {types.data?.length === 0 && <p dir="auto">{text("尚未创建积分类型，请先在积分配置中创建。", "No points type exists. Create one in points configuration first.")}</p>}
    {!!types.data?.length && <>
      <label className="block">{text("积分类型", "Points type")} <Select value={typeCode} onChange={(e) => { setSelected(e.target.value); setAfter(0); save.reset(); }}>
        {types.data.map((t) => <option key={t.typeCode} value={t.typeCode}>{t.name} · {t.typeCode} · {t.market} · {t.status}</option>)}
      </Select></label>
      <form className="grid gap-3 rounded-card border border-border p-4 md:grid-cols-2" onSubmit={(e) => {
        e.preventDefault();
        const next = pointsCommand({ typeCode: global ? "*" : typeCode, scope, subjectNo: effectiveSubject, flow, enabled, reason: reason.trim() }, intent.current);
        intent.current = next.intent;
        save.mutate(next.command);
      }}>
        <label>{text("范围", "Scope")} <Select className="w-full" disabled={!canEdit} value={scope} onChange={(e) => { setScope(e.target.value as PointsScope); setSubject(""); save.reset(); }}>
          {SCOPES.map((s) => <option key={s}>{s}</option>)}
        </Select></label>
        <label>{text("范围对象编号（门店使用 storeNo）", "Subject ID (storeNo for a store)")}
          {(scope === "CLIENT" || scope === "SCENE") ? <Select className="w-full" value={effectiveSubject} disabled={!canEdit} onChange={(e) => setSubject(e.target.value)}>{subjects.map((s) => <option key={s}>{s}</option>)}</Select>
            : <Input dir="auto" required value={effectiveSubject} disabled={!canEdit || scope === "TYPE" || scope === "GLOBAL"} onChange={(e) => setSubject(e.target.value)} />}
        </label>
        <label>{text("方向", "Flow")} <Select className="w-full" disabled={!canEdit} value={flow} onChange={(e) => setFlow(e.target.value as PointsFlow)}>{["BOTH", "EARN", "REDEEM"].map((s) => <option key={s}>{s}</option>)}</Select></label>
        <label>{text("状态", "Availability")} <Select className="w-full" disabled={!canEdit} value={String(enabled)} onChange={(e) => setEnabled(e.target.value === "true")}>
          <option value="false">{text("关闭", "Disabled")}</option><option value="true">{text("开启（仍受其他范围限制）", "Enabled (other scope restrictions still apply)")}</option>
        </Select></label>
        <label>{text("操作原因", "Reason")} <Input dir="auto" required maxLength={1000} disabled={!canEdit} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
        {canEdit && <Button type="submit" disabled={save.isPending || !reason.trim() || !effectiveSubject}>{save.isPending ? c.ppSaving : c.ppSave}</Button>}
      </form>
      {save.isError && <Notice tone="warning">{save.error.message}</Notice>}
      {save.isSuccess && <Notice>{text("已保存，操作号：", "Saved, request ID: ")}{save.data.requestId}</Notice>}
      {list.isPending && <p dir="auto">{c.ppLoading}</p>}
      {list.isError && <Notice tone="warning">{list.error.message} <Button onClick={() => void list.refetch()}>{text("重试", "Retry")}</Button></Notice>}
      {list.data && <div className="overflow-x-auto"><DataTable rows={list.data.rows} error={list.error} onRetry={() => void list.refetch()}
          empty={text("此范围暂无开关记录；实际可用性还受类型状态、协议及资金限制。", "No switch records in this scope. Type state, agreement and funding still constrain availability.")} rowKey={(r) => String(r.id)} columns={[
          { header: text("积分名称", "Points name"), cell: (r) => r.typeCode === "*" ? text("全部积分", "All points") : types.data?.find(x => x.typeCode === r.typeCode)?.name ?? text("积分名称未加载", "Points name unavailable") },
          { header: text("积分代码", "Points code"), cell: (r) => r.typeCode },
          { header: text("范围", "Scope"), cell: (r) => r.scope },
          { header: text("对象", "Subject"), cell: (r) => r.subjectNo },
          { header: text("方向", "Flow"), cell: (r) => r.flow },
          { header: text("状态", "Availability"), cell: (r) => r.enabled ? text("开启", "Enabled") : text("关闭", "Disabled") },
          { header: text("操作人 / 原因", "Operator / reason"), cell: (r) => `${r.actor} / ${r.reason}` },
          { header: text("服务端时间", "Server time"), cell: (r) => new Date(r.at).toLocaleString(en ? "en" : "zh-CN") },
        ]} />
        {!list.data.rows.length && <p dir="auto" className="p-3">{text("此范围暂无开关记录；实际可用性还受类型状态、协议及资金限制。", "No switch records in this scope. Type state, agreement and funding still constrain availability.")}</p>}
        <div className="flex gap-2"><Button disabled={!after} onClick={() => setAfter(0)}>{text("首页", "First page")}</Button><Button disabled={!list.data.hasMore || list.isFetching} onClick={() => setAfter(list.data.next)}>{text("下一页", "Next page")}</Button></div>
      </div>}
    </>}
  </div>;
}
