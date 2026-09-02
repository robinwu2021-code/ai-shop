"use client";

// 门店主页治理（矩阵 P-10.1）—— 一期的**主获客路径**（ADR-004：增长靠商家自带客流）。
// 平台侧管三件事：店招/公告的合规审核、店铺码供给（BD 地推印刷）、获客效果度量。
// 主页模板配置（P-10.1.1）故意不做：C 端门店主页未定稿，先做模板等于两头返工。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCopy, fill } from "@/lib/use-copy";
import { STORES_COPY } from "./copy";
import { buildPrintSheetHtml } from "@/lib/store-print-sheet";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
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
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Progress } from "@/components/ui/progress";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof STORES_COPY)["zh"];
const TAB_KEYS = ["audit", "template", "qrcode", "effect"] as const;

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
  const tabs = useNavTabs("/stores", TAB_KEYS);
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
  // 印刷量登记：哪一行正在登记（null = 对话框关着）
  const [printFor, setPrintFor] = useState<StoreQrcode | null>(null);
  const [printQty, setPrintQty] = useState("");
  const [printSize, setPrintSize] = useState("");
  const [printRemark, setPrintRemark] = useState("");

  /*
   * 获客看板带时间区间。**不给区间不等于「有史以来」** ——
   * 累计值只会越来越大，判断不了这一轮投放有没有效果。
   * 取值放 state 里而不是每次渲染现算 Date.now()：现算的话每次渲染都是新的 queryKey，
   * react-query 会当成新查询一直重取。
   */
  const [acqDays, setAcqDays] = useState(30);
  const [acqTo] = useState(() => Date.now());
  const acqQ = { keyword, page, size, from: acqTo - acqDays * 86_400_000, to: acqTo };
  const acq = useQuery({
    queryKey: ["store-acq", acqQ],
    queryFn: () => api.listStoreAcquisition(acqQ),
    enabled: tab === "effect",
  });

  // 店铺码页的扫码数与获客看板同一个区间口径 —— 两页给出不同的「扫码数」会当场引出
  // 「到底哪个对」，而两个都对，只是窗口不同
  const [codeless, setCodeless] = useState(false);
  const qrcodeQ = { keyword, codeless, page, size, from: acqTo - acqDays * 86_400_000, to: acqTo };
  const qrcodes = useQuery({
    queryKey: ["store-qrcodes", qrcodeQ],
    queryFn: () => api.listStoreQrcodes(qrcodeQ),
    enabled: tab === "qrcode",
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

  const [reissueFor, setReissueFor] = useState<StoreQrcode | null>(null);
  const [reissueReason, setReissueReason] = useState("");

  const issue = useMutation({
    mutationFn: (r: StoreQrcode) =>
      api.issueStoreQrcode({ merchantNo: r.merchantNo, storeNo: r.storeNo }),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["store-qrcodes"] });
      notify.success(fill(c.issued, { code: r.storeCode }));
    },
  });

  const reissue = useMutation({
    mutationFn: () =>
      api.reissueStoreQrcode({
        merchantNo: reissueFor!.merchantNo,
        storeNo: reissueFor!.storeNo,
        reason: reissueReason.trim(),
      }),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["store-qrcodes"] });
      setReissueFor(null);
      notify.success(fill(c.reissued, { code: r.storeCode }));
    },
  });

  /*
   * 可印刷页。**CSV 装不下图** —— 而「导出」这个动作的用途就是把物料交给印刷，
   * 拿到五列文本还得再找人配图，等于没导。
   *
   * 另开一个窗口写一张打印页：没有码图的行显示「无码图」而不是留空，
   * 留空会被当成印刷失误，而真实原因是那家店还没发码。
   */
  const printSheet = async () => {
    const rows = await api.exportStoreQrcodes({ keyword, codeless, from: qrcodeQ.from, to: qrcodeQ.to });
    const w = window.open("", "_blank");
    if (!w) {
      /*
       * **拦截要说出来。** 静默 return 的表现是「点了没反应」——
       * 运营会以为这批店没有码图，而真正的原因是浏览器拦了弹窗。
       */
      notify.error(c.printSheetBlocked);
      return;
    }
    w.document.write(buildPrintSheetHtml(
      rows.map(({ row, imageBase64 }) => ({
        storeNo: row.storeNo,
        storeName: row.storeName,
        merchantName: row.merchantName,
        code: row.code,
        imageBase64,
      })),
      { title: c.printSheetTitle, empty: c.printSheetEmpty, noImage: c.printSheetNoImage },
    ));
    w.document.close();
  };

  const recordPrint = useMutation({
    mutationFn: () =>
      api.recordQrcodePrint({
        merchantNo: printFor!.merchantNo,
        storeNo: printFor!.storeNo,
        qty: Number(printQty),
        size: printSize.trim() || undefined,
        remark: printRemark.trim() || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["store-qrcodes"] });
      setPrintFor(null);
      notify.success(c.printDone);
    },
  });

  const auditColumns: Column<StorePageAudit>[] = [
    { header: c.colAuditNo, cell: (a) => a.auditNo, numeric: true, align: "start" },
    {
      header: c.colMerchant,
      // 门店名跟在商家名后面，不单开一列：多数商家只有一家店，那一列会常年空着
      cell: (a) => (
        <span>
          {a.merchantName}
          {a.storeName ? <span className="text-muted-foreground"> · {a.storeName}</span> : null}
        </span>
      ),
    },
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
    // V298 一行一店：不给门店列的话，同一商家的几行长得一模一样
    { header: c.colStore, cell: (r) => r.storeName ?? r.storeNo },
    {
      header: c.colCode,
      // ★ null = 还没发过码，与「有码但没印」必须分开 —— 前者要发码，后者要催印
      cell: (r) => (r.code
        ? <code className="txt-caption">{r.code}</code>
        : <span className="text-[var(--warning)]">{c.codeUnset}</span>),
    },
    { header: c.colSize, cell: (r) => r.size },
    {
      header: c.colPrinted,
      numeric: true,
      // ★ null 是「还没人登记」，与「印了 0 张」必须分开显示 ——
      //   混成一个 0，运营就不知道该去催谁登记
      cell: (r) =>
        r.printed == null
          ? <span className="text-muted-foreground">{c.printedUnset}</span>
          : r.printed,
    },
    { header: c.colScanCount, cell: (r) => r.scanCount, numeric: true },
    {
      header: c.colActions,
      cell: (r) =>
        canExport ? (
          <div className="flex gap-2">
            {r.code == null ? (
              // 没码就先发码 —— 登记印量、导出印刷页在这一行上都还无从谈起
              <Button size="sm" variant="outline" disabled={issue.isPending}
                      onClick={() => issue.mutate(r)}>
                {c.issueAction}
              </Button>
            ) : (
              <>
                <Button size="sm" variant="outline" onClick={() => { setPrintFor(r); setPrintQty(""); setPrintSize(r.size ?? ""); setPrintRemark(""); }}>
                  {c.printAction}
                </Button>
                {/* 换码单独一颗且要走确认：它让已印物料全部失效 */}
                <Button size="sm" variant="outline" onClick={() => { setReissueFor(r); setReissueReason(""); }}>
                  {c.reissueAction}
                </Button>
              </>
            )}
          </div>
        ) : null,
    },
  ];

  const acqColumns: Column<StoreAcquisition>[] = [
    { header: c.colMerchant, cell: (r) => r.merchantName },
    { header: c.colScan, cell: (r) => r.scan, numeric: true },
    // 人数与次数分开列：只给次数的话，一个人反复扫会被当成「很多人来过」
    { header: c.colScanUv, cell: (r) => r.scanUv, numeric: true },
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

      {/* 口径常驻：这几个数很容易被读成别的意思（尤其「首次归因」不是新注册） */}
      {tab === "effect" && <Notice className="mb-3">{c.acqNotice}</Notice>}

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
                    { header: c.colStore, value: (r: StoreQrcode) => r.storeName ?? r.storeNo },
                    // 没码的行导出「待发码」而不是空白：空白会被当成漏填
                    { header: c.colCode, value: (r: StoreQrcode) => r.code ?? c.codeUnset },
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
        {tab === "qrcode" && (
          <>
            {/* 待发码是「要动手」的清单，不是一个可有可无的筛子 */}
            <FilterSelect
              aria-label={c.filterCodeless}
              value={codeless ? "1" : ""}
              onChange={(v) => { setCodeless(v === "1"); setPage(1); }}
              options={[{ value: "1", label: c.filterCodeless }]}
              allLabel={c.filterStatusAll}
            />
          </>
        )}
        {tab === "effect" && (
          <FilterSelect
            aria-label={c.acqRange30}
            value={String(acqDays)}
            onChange={(v) => { setAcqDays(Number(v)); setPage(1); }}
            options={[
              { value: "7", label: c.acqRange7 },
              { value: "30", label: c.acqRange30 },
              { value: "90", label: c.acqRange90 },
            ]}
          />
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
      {tab === "qrcode" && canExport && (
        // 印刷页是**动作**不是筛子，所以不放进 Toolbar：
        // Toolbar 里的控件会被当成筛选项要求回显选中态（design-tokens 那道闸）
        <div className="mb-3 flex justify-end">
          <Button size="sm" variant="outline" onClick={() => void printSheet()}>
            {c.printSheet}
          </Button>
        </div>
      )}

      {tab === "qrcode" && (
        <DataTable
          columns={qrcodeColumns}
          rows={qrcodes.data?.records}
          loading={qrcodes.isLoading}
          error={qrcodes.error}
          onRetry={() => qrcodes.refetch()}
          // ★ 一行一店之后 merchantNo 不再唯一：多门店商家会出现重复 key，
          //   React 把两行当成同一行合并，点「发码」作用在另一家店上且不报错
          rowKey={(r) => r.storeNo}
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

      {/* 印刷量登记：线下事实，系统无从自动知道，只能人录 */}
      <Drawer
        open={!!printFor}
        onOpenChange={(o) => !o && setPrintFor(null)}
        title={printFor ? `${printFor.merchantName} · ${c.printTitle}` : ""}
        desc={printFor?.code ?? undefined}
        footer={
          <Button
            // 空与 0 都不提交：0 既不是印了也不是冲减，后端也会拒
            disabled={!printQty.trim() || Number(printQty) === 0 || Number.isNaN(Number(printQty)) || recordPrint.isPending}
            onClick={() => recordPrint.mutate()}
          >
            {c.printSubmit}
          </Button>
        }
      >
        {printFor && (
          <div className="space-y-3">
            <Field label={c.printQty}>
              <Input value={printQty} onChange={(e) => setPrintQty(e.target.value)} placeholder="200" />
              <p className="mt-1 txt-caption text-muted-foreground">{c.printQtyHint}</p>
            </Field>
            <Field label={c.printSize}>
              <Input value={printSize} onChange={(e) => setPrintSize(e.target.value)} placeholder="10x10cm" />
            </Field>
            <Field label={c.printRemark}>
              <Textarea value={printRemark} onChange={setPrintRemark} />
            </Field>
          </div>
        )}
      </Drawer>

      {/*
        换码。**单独一个抽屉而不是一次 confirm**：它让已经贴在店里的物料全部失效，
        代价在线下，而线上只是一次点击 —— 要求写清楚原因，是让这一步慢下来的唯一办法。
      */}
      <Drawer
        open={!!reissueFor}
        onOpenChange={(o) => !o && setReissueFor(null)}
        title={reissueFor ? fill(c.reissueTitle, { store: reissueFor.storeName ?? reissueFor.storeNo }) : ""}
        desc={reissueFor?.code ?? undefined}
        footer={
          <Button
            variant="destructive"
            disabled={!reissueReason.trim() || reissue.isPending}
            onClick={() => reissue.mutate()}
          >
            {c.reissueConfirm}
          </Button>
        }
      >
        {reissueFor && (
          <div className="space-y-3">
            <Notice tone="danger">{c.reissueWarn}</Notice>
            <Field label={c.reissueReason}>
              <Textarea value={reissueReason} onChange={setReissueReason} />
            </Field>
          </div>
        )}
      </Drawer>
    </div>
  );
}
