"use client";

// 门店主页治理（矩阵 P-10.1）—— 一期的**主获客路径**（ADR-004：增长靠商家自带客流）。
// 平台侧管三件事：店招/公告的合规审核、店铺码供给（BD 地推印刷）、获客效果度量。
// 主页模板配置（P-10.1.1）故意不做：C 端门店主页未定稿，先做模板等于两头返工。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCopy } from "@/lib/use-copy";
import { STORES_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { StoreAcquisition, StorePageAudit, StoreQrcode } from "@/lib/types";
import { StoreAuditStatusBadge, useStoreAuditStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 模板配置自成一块 —— 与审核/店铺码/效果三个 tab 只共用文案表
import { TemplateTab } from "./template-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Progress } from "@/components/ui/progress";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof STORES_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "audit", label: c.tabAudit },
  { key: "template", label: c.tabTemplate },
  { key: "qrcode", label: c.tabQrcode },
  { key: "effect", label: c.tabEffect },
];

/** 三种待审内容的标签。写成函数是因为它在列表、详情标题两处都要用 —— 两处各写一遍必然分岔 */
function kindLabel(kind: string, c: Copy) {
  if (kind === "BANNER") return c.kindBanner;
  if (kind === "SERVICE_AREA") return c.kindArea;
  return c.kindNotice;
}

const KIND_OPTIONS = (c: Copy) => [
  { value: "BANNER", label: c.kindBanner },
  { value: "NOTICE", label: c.kindNotice },
  { value: "SERVICE_AREA", label: c.kindArea },
];

export default function StoresPage() {
  return <Suspense fallback={null}><StoresInner /></Suspense>;
}

function StoresInner() {
  const c = useCopy(STORES_COPY);
  const tabs = TABS(c);
  const kindOptions = KIND_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [kind, setKind] = useState("");
  // 审核页默认只看待审：它是**队列**，历史是次要视图
  const [status, setStatus] = useState("PENDING");
  const [current, setCurrent] = useState<StorePageAudit | null>(null);
  const [reason, setReason] = useState("");

  const canAudit = allow("store:page:audit");
  const canExport = allow("store:qrcode:export");
  // 模板配置沿用店招审核的权限：都是「平台改商家门面」这一类动作
  const canTemplate = allow("store:page:audit");
  const statusMap = useStoreAuditStatusMap();

  const auditQ = { keyword, kind, status, page, size };
  const audits = useQuery({
    queryKey: ["store-audits", auditQ],
    queryFn: () => api.listStoreAudits(auditQ),
    enabled: tab === "audit",
  });
  const qrcodeQ = { keyword, page, size };
  const qrcodes = useQuery({
    queryKey: ["store-qrcodes", qrcodeQ],
    queryFn: () => api.listStoreQrcodes(qrcodeQ),
    enabled: tab === "qrcode",
  });
  const acq = useQuery({
    queryKey: ["store-acq", qrcodeQ],
    queryFn: () => api.listStoreAcquisition(qrcodeQ),
    enabled: tab === "effect",
  });

  const decide = useMutation({
    mutationFn: (v: { auditNo: string; pass: boolean; reason?: string }) =>
      api.decideStoreAudit(v.auditNo, v.pass, v.reason),
    onSuccess: (a) => {
      qc.invalidateQueries({ queryKey: ["store-audits"] });
      setCurrent(null);
      setReason("");
      notify.success(a.status === "PASSED" ? c.toastPassed : c.toastRejected);
    },
  });

  const auditColumns: Column<StorePageAudit>[] = [
    { header: c.colAuditNo, cell: (a) => a.auditNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (a) => a.merchantName },
    { header: c.colKind, cell: (a) => kindLabel(a.kind, c) },
    {
      header: c.colContent,
      width: "22rem",
      cell: (a) => <span className="line-clamp-1 text-muted-foreground">{a.display ?? a.content}</span>,
    },
    {
      header: c.colHits,
      // 把机器标它的**理由**摆出来。只给一个"疑似违规"标记，人审只能凭感觉判，
      // 同一类内容两个人会给两个结论。
      cell: (a) =>
        a.hits.length === 0 ? (
          <span className="text-muted-foreground">{c.none}</span>
        ) : (
          <span className="flex flex-wrap gap-1">
            {a.hits.map((h) => <Badge key={h} tone="warning">{h}</Badge>)}
          </span>
        ),
    },
    { header: c.colSubmittedAt, cell: (a) => fmtTime(a.submittedAt) },
    { header: c.colStatus, cell: (a) => <StoreAuditStatusBadge value={a.status} /> },
    {
      header: c.colActions,
      cell: (a) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(a); setReason(a.reason ?? ""); }}>
          {a.status === "PENDING" && canAudit ? c.actionAudit : c.actionView}
        </Button>
      ),
    },
  ];

  const qrcodeColumns: Column<StoreQrcode>[] = [
    { header: c.colMerchantNo, cell: (r) => r.merchantNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (r) => r.merchantName },
    { header: c.colCommunity, cell: (r) => r.communityName },
    { header: c.colCode, cell: (r) => <code className="txt-caption">{r.code}</code> },
    { header: c.colSize, cell: (r) => r.size },
    { header: c.colPrinted, cell: (r) => r.printed, numeric: true },
    { header: c.colScanCount, cell: (r) => r.scanCount, numeric: true },
  ];

  const acqColumns: Column<StoreAcquisition>[] = [
    { header: c.colMerchant, cell: (r) => r.merchantName },
    { header: c.colScan, cell: (r) => r.scan, numeric: true },
    { header: c.colEnter, cell: (r) => r.enter, numeric: true },
    { header: c.colRegister, cell: (r) => r.register, numeric: true },
    { header: c.colFirstOrder, cell: (r) => r.firstOrder, numeric: true },
    {
      header: c.colConversion,
      width: "12rem",
      cell: (r) => (
        <div className="flex items-center gap-2">
          <Progress value={Math.round(r.convRate * 100)} total={100} showText={false} className="w-24" />
          <span className="tabular-nums text-muted-foreground">{(r.convRate * 100).toFixed(1)}%</span>
        </div>
      ),
    },
  ];

  const activeList = tab === "audit" ? audits : tab === "qrcode" ? qrcodes : acq;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "audit" && !canAudit && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="store:page:audit" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "qrcode" && (
        <Notice className="mb-3">
          {c.notice}
        </Notice>
      )}

      {tab === "template" && (
        <>
          {!canTemplate && <ReadOnlyNotice what={c.templateReadOnlyWhat} perm="store:page:audit" className="mb-3" />}
          <TemplateTab c={c} canEdit={canTemplate} />
        </>
      )}

      {tab !== "template" && (
      <>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={tab === "audit" ? c.searchAudit : c.searchQrcode}
        onExport={
          tab === "qrcode" && canExport
            ? () =>
                exportCsv(
                  c.exportSheet,
                  [
                    { header: c.colMerchantNo, value: (r: StoreQrcode) => r.merchantNo },
                    { header: c.colMerchant, value: (r: StoreQrcode) => r.merchantName },
                    { header: c.colCommunity, value: (r: StoreQrcode) => r.communityName },
                    { header: c.colCode, value: (r: StoreQrcode) => r.code },
                    { header: c.colSize, value: (r: StoreQrcode) => r.size },
                  ],
                  qrcodes.data?.records ?? [],
                )
            : undefined
        }
        exportLabel={c.exportLabel}
      >
        {tab === "audit" && (
          <>
            <FilterSelect aria-label={c.filterKind} value={kind} onChange={(v) => { setKind(v); setPage(1); }} options={kindOptions} allLabel={c.filterKindAll} />
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
          </>
        )}
      </Toolbar>

      {tab === "audit" && (
        <DataTable
          columns={auditColumns}
          rows={audits.data?.records}
          loading={audits.isLoading}
          error={audits.error}
          onRetry={() => audits.refetch()}
          rowKey={(a) => a.auditNo}
          empty={c.emptyAudit}
        />
      )}
      {tab === "qrcode" && (
        <DataTable
          columns={qrcodeColumns}
          rows={qrcodes.data?.records}
          loading={qrcodes.isLoading}
          error={qrcodes.error}
          onRetry={() => qrcodes.refetch()}
          rowKey={(r) => r.merchantNo}
          empty={c.emptyQrcode}
        />
      )}
      {tab === "effect" && (
        <DataTable
          columns={acqColumns}
          rows={acq.data?.records}
          loading={acq.isLoading}
          error={acq.error}
          onRetry={() => acq.refetch()}
          rowKey={(r) => r.merchantNo}
          empty={c.emptyEffect}
        />
      )}

      <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />
      </>
      )}

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? `${current.merchantName} · ${kindLabel(current.kind, c)}` : ""}
        desc={current?.auditNo}
        footer={
          current?.status === "PENDING" && canAudit ? (
            <>
              <Button
                variant="outline"
                onClick={() => decide.mutate({ auditNo: current.auditNo, pass: false, reason })}
              >
                {c.btnReject}
              </Button>
              <Button onClick={() => decide.mutate({ auditNo: current.auditNo, pass: true })}>{c.btnPass}</Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <Field label={c.fieldPending}>
              {current.kind === "SERVICE_AREA" ? (
                /* 覆盖项审的是「这家店能不能做这一片」，所以给的是地名 + 判据提示，
                   而不是像公告那样把原文摆出来 —— 原文是 DISTRICT:330106，看不出任何东西 */
                <div className="space-y-2">
                  <p className="font-medium">{current.display ?? current.content}</p>
                  <p className="txt-caption text-muted-foreground">{c.areaHint}</p>
                </div>
              ) : current.kind === "BANNER" ? (
                // 图片走 CDN，本地 mock 里是假的 URL：显示地址本身而不是加载失败的破图
                <code className="break-all txt-caption text-muted-foreground">{current.content}</code>
              ) : (
                <p className="whitespace-pre-wrap">{current.content}</p>
              )}
            </Field>
            <Field label={c.fieldHits}>
              {current.hits.length === 0 ? c.none : (
                <span className="flex flex-wrap gap-1">
                  {current.hits.map((h) => <Badge key={h} tone="warning">{h}</Badge>)}
                </span>
              )}
            </Field>
            <Field label={c.colSubmittedAt}>{fmtTime(current.submittedAt)}</Field>
            <Field label={c.fieldRejectReason}>
              {current.status === "PENDING" && canAudit ? (
                <Textarea
                  value={reason}
                  onChange={setReason}
                  placeholder={c.rejectPlaceholder}
                />
              ) : (
                current.reason || "-"
              )}
            </Field>
          </div>
        )}
      </Drawer>
    </div>
  );
}
