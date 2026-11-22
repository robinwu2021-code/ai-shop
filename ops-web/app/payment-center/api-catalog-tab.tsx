"use client";

// API 清单（SY12）与调用模拟（SY13），P12。清单是 near-pay 对外开放 API 的只读说明（契约真源 openapi-pay-open.yaml v1.2）；
// 行操作：查看（参数、请求体、样例、公共头与错误码）· 调用模拟（抽屉：浏览器内签名、出 curl；mock 下可直接发给 mock 网关）。
// 不调用任何运营接口；调用模拟只需页签查看权限（它不碰平台数据，密钥由使用者自己填）。
import * as React from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Badge } from "@/components/ui/badge";
import { DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage } from "@/app/payment-center/_kit/pay-kit";
import { COMMON_HEADERS, OPEN_ENDPOINTS, OPEN_ERROR_CODES, resolveOpenApiBase, type ApiField, type ApiParam, type L, type OpenEndpoint } from "@/app/payment-center/_api/open-api-catalog";
import { useI18n } from "@/lib/i18n";
import { toTraditional } from "@/lib/i18n/zh-tw";
import { useCopy, fill } from "@/lib/use-copy";
import { MiniTable, usePayCenterApi, usePc } from "./shared";
import { P12_COPY } from "./p12-copy";
import { ApiSimulator } from "./api-simulator";

function useL() {
  const { locale } = useI18n();
  return React.useCallback((v: L) => (locale === "en" ? v.en : locale === "zh-TW" ? toTraditional(v.zh) : v.zh), [locale]);
}

export function ApiCatalogTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const l = useL();
  const api = usePayCenterApi();
  const base = React.useMemo(() => resolveOpenApiBase(), []);
  const [page, setPage] = React.useState(1);
  const [size, setSize] = React.useState(20);
  const [keyword, setKeyword] = React.useState("");
  const query = useQuery({
    queryKey: ["pay-center", "api-catalog", keyword, page, size],
    queryFn: () => {
      const kw = keyword.trim().toLowerCase();
      const hit = OPEN_ENDPOINTS.filter((e) => !kw || e.path.toLowerCase().includes(kw) || e.title.zh.includes(kw) || e.title.en.toLowerCase().includes(kw));
      return Promise.resolve({ records: hit.slice((page - 1) * size, page * size), total: hit.length });
    },
    placeholderData: keepPreviousData,
  });

  const actions: ActionSpec<OpenEndpoint>[] = [
    { key: "view", label: c.view, kind: "drawer", width: "w-[860px]", title: (e) => (e ? l(e.title) : m.endpointTitle), render: (ctx) => <EndpointView e={ctx.row!} prefix={base.base} /> },
    { key: "simulate", label: m.simulate, kind: "drawer", width: "w-[860px]", title: (e) => fill(m.simulateTitle, { title: e ? l(e.title) : "" }),
      render: (ctx) => <ApiSimulator endpoint={ctx.row!} mock={api.source === "mock"} onDone={ctx.back ?? ctx.close} /> },
  ];

  const columns: Column<OpenEndpoint>[] = [
    { header: m.endpoint, cell: (e) => l(e.title) },
    { header: m.method, cell: (e) => <Badge tone={e.method === "GET" ? "info" : "warning"}>{e.method}</Badge> },
    { header: m.path, cell: (e) => <span className="font-mono txt-caption">{base.prefix}{e.path}</span> },
    { header: m.scope, cell: (e) => <span className="font-mono txt-caption">{e.scope ?? m.scopeNone}</span> },
    { header: m.applyScope, cell: (e) => e.action ?? "—" },
    { header: "", cell: (e) => (e.sandboxOnly ? <Badge tone="muted">{m.sandboxOnly}</Badge> : e.sensitive?.length ? <Badge tone="muted">P3</Badge> : null) },
  ];

  return (
    <PayListPage<OpenEndpoint>
      header={header}
      help={fill(m.catalogDesc, { base: base.base, prefix: base.prefix })}
      search={keyword}
      onSearch={(v) => { setKeyword(v); setPage(1); }}
      searchPlaceholder={m.catalogSearch}
      query={query}
      page={page}
      size={size}
      onPage={setPage}
      onSize={(n) => { setSize(n); setPage(1); }}
      columns={columns}
      rowKey={(e) => e.key}
      actions={actions}
      resolve={(id) => OPEN_ENDPOINTS.find((e) => e.key === id) ?? null}
      empty={m.catalogEmpty}
    />
  );
}

function EndpointView({ e, prefix }: { e: OpenEndpoint; prefix: string }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const l = useL();
  const pre = "max-h-60 overflow-auto whitespace-pre-wrap break-all rounded-field bg-muted p-3 font-mono txt-caption";
  return (
    <DetailBody sections={[
      { title: c.sectionBasic, fields: [
        { label: m.path, value: <span className="font-mono break-all">{e.method} {prefix}{e.path}</span>, full: true },
        { label: m.description, value: l(e.summary), full: true },
        { label: m.scope, value: e.scope ?? m.scopeNone },
        { label: m.applyScope, value: e.action ?? "—" },
        { label: m.idempotency, value: e.idempotency ?? "—" },
        { label: m.sandboxOnly, value: e.sandboxOnly ? c.yes : c.no },
        { label: m.sensitiveFields, value: e.sensitive?.join(", ") || "—", full: true },
      ] },
      { title: m.sectionParams, content: <MiniTable<ApiParam> rows={e.params} rowKey={(p) => p.name} empty={m.noParams} columns={[
        { header: "name", cell: (p) => <span className="font-mono">{p.name}</span> },
        { header: m.paramIn, cell: (p) => p.in },
        { header: m.required, cell: (p) => (p.required ? c.yes : c.no) },
        { header: m.description, cell: (p) => l(p.description) },
      ]} /> },
      { title: m.sectionBody, content: <MiniTable<ApiField> rows={e.body} rowKey={(f) => f.name} empty={m.noBody} columns={[
        { header: "name", cell: (f) => <span className="font-mono">{f.name}</span> },
        { header: m.type, cell: (f) => f.type },
        { header: m.required, cell: (f) => (f.required ? c.yes : c.no) },
        { header: m.description, cell: (f) => l(f.description) },
      ]} /> },
      { title: m.sectionSample, content: <pre className={pre}>{JSON.stringify({ code: 0, message: "OK", data: e.sampleResponse, requestId: "…" }, null, 2)}</pre> },
      { title: m.sectionHeaders, content: <MiniTable rows={COMMON_HEADERS} rowKey={(h) => h.name} empty={m.noParams} columns={[
        { header: "header", cell: (h) => <span className="font-mono">{h.name}</span> },
        { header: m.required, cell: (h) => l(h.required) },
        { header: m.description, cell: (h) => l(h.description) },
      ]} /> },
      { title: m.sectionErrors, content: <MiniTable rows={OPEN_ERROR_CODES} rowKey={(x) => String(x.code)} empty={m.noParams} columns={[
        { header: "HTTP", cell: (x) => x.http },
        { header: "code", cell: (x) => <span className="font-mono">{x.code} {x.name}</span> },
        { header: m.description, cell: (x) => l(x.when) },
      ]} /> },
    ]} />
  );
}
