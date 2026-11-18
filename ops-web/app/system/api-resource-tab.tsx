"use client";

// 网关接口资源（TDD-网关层与统一权限 §2.23）—— 已接真后端 `/ops/api-resources/**`。
//
// 这一页回答两个问题：网关认得哪些链接、每条要什么权限；以及出事时把某一条停掉。
// 权限要求是代码（本进程入口）或清单（微服务链接）定的，这里只读 —— 能改的只有停用与恢复。
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
import type { ApiResource } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FilterSelect } from "@/components/ui/filter-select";
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

  const accessLabel: Record<ApiResource["access"], string> = {
    PUBLIC: c.arAccessPublic, LOGIN: c.arAccessLogin, POINT: c.arAccessPoint, DEFER: c.arAccessDefer,
  };

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
          <div>{accessLabel[r.access] ?? r.access}</div>
          {r.ruleText && <code className="txt-caption text-muted-foreground">{r.ruleText}</code>}
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
      cell: (r: ApiResource) => (r.locked && r.status === "ENABLED"
        ? <span className="txt-caption text-muted-foreground">{c.arLocked}</span>
        : (
          <Button size="sm" variant="outline" onClick={() => toggle(r)}>
            {r.status === "ENABLED" ? c.arDisableAct : c.arEnableAct}
          </Button>
        )),
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
            { value: "biz", label: c.arEndBiz }]} allLabel={c.arEndAll} />
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
      {dialog}
    </>
  );
}
