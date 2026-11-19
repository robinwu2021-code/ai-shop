"use client";

// 网关接口资源（TDD-网关层与统一权限 §2.23）—— 已接真后端 `/ops/api-resources/**`。
//
// 这一页回答两个问题：网关认得哪些链接、每条要什么权限；以及出事时把某一条停掉。
// 权限要求的初始值来自代码（本进程入口）或清单（微服务链接）。能改两件事：停用与恢复；
// 以及在库里调整某条本进程入口的规则（`POST /ops/api-resources/{id}/rule`）—— 网关按调整后的判，
// 业务代码与入口注解不动。微服务链接与不可停用的行不能调整，后端也会拒绝。
//
// 停用对本进程入口只在「提前判权」开到观察或拦截时生效。当前是哪一档由后端告诉页面，
// 顶部据此如实说明：不能让人以为点了就停了。
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { usePaging } from "@/lib/use-paging";
import type { ApiResource, ApiResourceAdjustableAccess } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FilterSelect } from "@/components/ui/filter-select";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { SystemCopy } from "./copy";

export function ApiResourceTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [endCode, setEndCode] = useState("");
  const [source, setSource] = useState("");
  const [status, setStatus] = useState("");
  const { page, setPage, size, setSize } = usePaging();

  const q = { keyword: keyword.trim() || undefined, endCode: endCode || undefined, source: source || undefined,
    status: status || undefined, page, size };
  const list = useQuery({ queryKey: ["api-resources", q], queryFn: () => api.listApiResources(q) });
  const summary = useQuery({ queryKey: ["api-resource-summary"], queryFn: () => api.getApiResourceSummary() });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["api-resources"] });
    qc.invalidateQueries({ queryKey: ["api-resource-summary"] });
  };

  const toggle = (r: ApiResource) => {
    const disable = r.status === "ENABLED";
    return confirm({
      title: disable ? c.arDisableTitle : c.arEnableTitle,
      desc: fill(disable ? c.arDisableBody : c.arEnableBody, { link: `${r.method} ${r.pattern}` }),
      danger: disable,
      action: () => api.setApiResourceStatus(r.id, disable ? "DISABLED" : "ENABLED")
        .then(() => { invalidate(); notify.success(disable ? c.arDisabledToast : c.arEnabledToast); }),
    });
  };

  // 调整规则的抽屉：access 为空串 = 撤销调整
  const [editing, setEditing] = useState<ApiResource | null>(null);
  const [form, setForm] = useState<Record<string, unknown>>({});
  const [saving, setSaving] = useState(false);
  // 这条接口眼下生效的权限式：切到「需要权限码」时带进输入框，在它的基础上改，不用从头输入
  const currentRule = (r: ApiResource) => r.adjustedRule ?? r.ruleText ?? "";
  const openAdjust = (r: ApiResource) => {
    setEditing(r);
    setForm({ link: `${r.method} ${r.pattern}`, access: r.adjustedAccess ?? "", rule: currentRule(r) });
  };
  // 表单在字段被禁用时会清空它的值（「不调整」时权限式是禁用的），所以切回「需要权限码」时补回去
  const changeAdjust = (next: Record<string, unknown>) => {
    const refill = next.access === "POINT" && !String(next.rule ?? "").trim() && editing;
    setForm(refill ? { ...next, rule: currentRule(editing) } : next);
  };
  const saveAdjust = () => {
    if (!editing) return;
    const access = (form.access as ApiResourceAdjustableAccess | "") || null;
    const rule = String(form.rule ?? "").trim();
    if (access === "POINT" && !rule) { notify.error(c.arAdjustRuleRequired); return; }
    setSaving(true);
    api.adjustApiResourceRule(editing.id, access, access === "POINT" ? rule : null)
      .then(() => { invalidate(); setEditing(null); notify.success(access ? c.arAdjustedToast : c.arAdjustResetToast); })
      // 后端读不懂权限式时只回「请求参数有误」：在需要权限码时把它说清楚
      .catch((e: unknown) => notify.error(access === "POINT" ? c.arAdjustRuleInvalid
        : e instanceof Error ? e.message : String(e)))
      .finally(() => setSaving(false));
  };

  const accessLabel: Record<ApiResource["access"], string> = {
    PUBLIC: c.arAccessPublic, LOGIN: c.arAccessLogin, POINT: c.arAccessPoint, DEFER: c.arAccessDefer,
  };

  const adjustFields: FieldDef[] = [
    { key: "link", label: c.arColLink, readOnlyOnEdit: true },
    { key: "access", label: c.arAdjustAccess, type: "select", options: [
      { value: "", label: c.arAdjustReset }, { value: "LOGIN", label: c.arAccessLogin },
      { value: "POINT", label: c.arAccessPoint }, { value: "DEFER", label: c.arAccessDefer }] },
    { key: "rule", label: c.arAdjustRule, type: "textarea", rows: 3, maxLength: 1000, help: c.arAdjustRuleHelp,
      disabledWhen: (v) => v.access !== "POINT" },
  ];

  const columns: Column<ApiResource>[] = [
    {
      header: c.arColLink, align: "start",
      cell: (r) => (
        <div>
          <code className="txt-caption">{r.method} {r.pattern}</code>
          {r.targetPath && <div className="txt-caption text-muted-foreground">→ {r.targetPath}</div>}
        </div>
      ),
    },
    { header: c.arColEnd, cell: (r) => r.endCode },
    {
      header: c.arColLocation,
      cell: (r) => (r.source === "MANUAL" ? <Badge tone="info">{r.location}</Badge> : c.arLocal),
    },
    {
      header: c.arColAccess, align: "start",
      cell: (r) => (
        <div>
          <div className="flex flex-wrap items-center gap-1">
            {accessLabel[r.adjustedAccess ?? r.access] ?? r.access}
            {r.adjustedAccess && <Badge tone="info">{c.arAdjusted}</Badge>}
          </div>
          {(r.adjustedAccess ? r.adjustedRule : r.ruleText) && (
            <code className="txt-caption text-muted-foreground">{r.adjustedAccess ? r.adjustedRule : r.ruleText}</code>
          )}
          {r.adjustedAccess && r.adjustedBy && (
            <div className="txt-caption text-muted-foreground">
              {fill(c.arAdjustedBy, { who: r.adjustedBy })}{r.adjustedAt ? ` · ${fmtTime(r.adjustedAt)}` : ""}
            </div>
          )}
          {r.admission && (
            <div className="txt-caption text-muted-foreground">{c.arAdmission} <code>{r.admission}</code></div>
          )}
        </div>
      ),
    },
    {
      header: c.arColStatus,
      cell: (r) => (
        <span className="flex flex-wrap items-center gap-1">
          {r.status === "DISABLED" ? <Badge tone="danger">{c.arDisabled}</Badge> : <Badge tone="success">{c.arEnabled}</Badge>}
          {r.stale && <Badge tone="warning">{c.arStale}</Badge>}
        </span>
      ),
    },
    {
      header: c.arColUpdated,
      cell: (r) => (r.updatedBy ? `${r.updatedBy} · ${fmtTime(r.updatedAt)}` : fmtTime(r.updatedAt)),
    },
    ...(canWrite ? [{
      header: c.arColActions,
      // 停用与调规则分开锁：没注解的入口不让停（扫描分不出哪条是登录），但不是会话外壳就可以调规则
      cell: (r: ApiResource) => (
        <span className="flex flex-wrap items-center gap-1">
          {r.locked && r.status === "ENABLED"
            ? <span className="txt-caption text-muted-foreground">{c.arLocked}</span>
            : (
              <Button size="sm" variant="outline" onClick={() => toggle(r)}>
                {r.status === "ENABLED" ? c.arDisableAct : c.arEnableAct}
              </Button>
            )}
          {r.adjustable && (
            <Button size="sm" variant="outline" onClick={() => openAdjust(r)}>{c.arAdjustAct}</Button>
          )}
        </span>
      ),
    }] : []),
  ];

  const s = summary.data;
  const effective = s ? s.precheck === "observe" || s.precheck === "enforce" : true;

  return (
    <>
      <HelpNote className="mb-3">{c.arNotice}</HelpNote>
      {s && (
        <Notice className="mb-3">
          {fill(c.arSummary, { total: s.total, disabled: s.disabled, stale: s.stale, manual: s.manual })}
          {" "}
          {effective ? c.arModeOn : c.arModeOff}
          {" "}
          {s.proxy ? c.arProxyOn : c.arProxyOff}
        </Notice>
      )}

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.arSearch}>
        <FilterSelect aria-label={c.arColEnd} value={endCode} onChange={(v) => { setEndCode(v); setPage(1); }}
          options={[{ value: "ops", label: c.arEndOps }, { value: "pay-ops", label: c.arEndPayOps },
            { value: "biz", label: c.arEndBiz }, { value: "mp", label: c.arEndMp }]} allLabel={c.arEndAll} />
        <FilterSelect aria-label={c.arColLocation} value={source} onChange={(v) => { setSource(v); setPage(1); }}
          options={[{ value: "SCAN", label: c.arLocal }, { value: "MANUAL", label: c.arRemote }]} allLabel={c.arSourceAll} />
        <FilterSelect aria-label={c.arColStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={[{ value: "ENABLED", label: c.arEnabled }, { value: "DISABLED", label: c.arDisabled }]} allLabel={c.arStatusAll} />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => String(r.id)}
        empty={c.arEmpty}
      />
      <Pagination page={page} size={size} total={list.data?.total ?? 0} onPage={setPage} onSize={setSize} />
      <FormDrawer
        open={!!editing}
        onOpenChange={(o) => { if (!o) setEditing(null); }}
        titleNew={c.arAdjustTitle}
        titleEdit={c.arAdjustTitle}
        isEdit
        fields={adjustFields}
        value={form}
        onChange={changeAdjust}
        onSubmit={saveAdjust}
        submitting={saving}
      />
      {dialog}
    </>
  );
}
