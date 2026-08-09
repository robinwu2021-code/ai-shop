"use client";

// 商品与类目（矩阵 P-3）。商家能入驻但没有商品池时，整条交易链路是空的 ——
// C 端逛不到东西、订单没有 SKU、团购没有商品可配。
//
// 多语言文案审核（P-3.2.5）**不单独成页**：它是商品抽屉里的一段（三语 + 回落提示）。
// 拆出去会变成「审文案时看不到商品本身」。
import { Suspense, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { PRODUCTS_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { MARKETS, type Category, type Market, type Sku } from "@/lib/types";
import { SkuStatusBadge, useCategoryTemplateMap, useSkuStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { GoodsAuditTab } from "./goods-audit-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";
import { Tree, type TreeNode } from "@/components/ui/tree";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof PRODUCTS_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "categories", label: c.tabCategories },
  { key: "skus", label: c.tabSkus },
  { key: "stock", label: c.tabStock },
  { key: "audit", label: c.tabGoodsAudit },
];

const MARKET_LABEL = (c: Copy): Record<Market, string> => ({ CN: c.marketCN, SG: c.marketSG });

export default function ProductsPage() {
  return <Suspense fallback={null}><ProductsInner /></Suspense>;
}

function ProductsInner() {
  const c = useCopy(PRODUCTS_COPY);
  const tabs = TABS(c);
  const marketLabel = MARKET_LABEL(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [current, setCurrent] = useState<Sku | null>(null);
  const [reason, setReason] = useState("");
  const [presale, setPresale] = useState<{ skuNo: string; quota: string; cutoffAt: string } | null>(null);

  const canAudit = allow("product:sku:audit");
  const canEditCategory = allow("product:category:update");
  const canEditStock = allow("product:stock:update");

  const templateMap = useCategoryTemplateMap();
  const statusMap = useSkuStatusMap();

  const cats = useQuery({ queryKey: ["categories"], queryFn: () => api.listCategories(), enabled: tab === "categories" });
  const skuQ = { keyword, status, page, size };
  const skus = useQuery({ queryKey: ["skus", skuQ], queryFn: () => api.listSkus(skuQ), enabled: tab === "skus" });
  const oversell = useQuery({ queryKey: ["oversell"], queryFn: () => api.listOversellSkus(), enabled: tab === "stock" });
  const presaleList = useQuery({
    queryKey: ["skus", "presale"],
    queryFn: () => api.listSkus({ size: 100 }),
    enabled: tab === "stock",
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["skus"] });
    qc.invalidateQueries({ queryKey: ["oversell"] });
    qc.invalidateQueries({ queryKey: ["categories"] });
  };

  const audit = useMutation({
    mutationFn: (v: { skuNo: string; pass: boolean; reason?: string }) => api.auditSku(v.skuNo, v.pass, v.reason),
    onSuccess: (s) => {
      invalidate(); setCurrent(null); setReason("");
      notify.success(s.status === "ON_SALE" ? c.toastApproved : c.toastRejected);
    },
  });
  const forceOff = useMutation({
    mutationFn: (v: { skuNo: string; reason: string }) => api.forceOffSku(v.skuNo, v.reason),
    onSuccess: () => { invalidate(); setCurrent(null); setReason(""); notify.success(c.toastForcedOff); },
  });
  const savePresale = useMutation({
    mutationFn: () => api.setSkuPresale(presale!.skuNo, Number(presale!.quota), presale!.cutoffAt),
    onSuccess: () => { invalidate(); setPresale(null); notify.success(c.toastPresaleSaved); },
  });
  const archiveCat = useMutation({
    mutationFn: (categoryNo: string) => api.archiveCategory(categoryNo),
    onSuccess: () => { invalidate(); notify.success(c.toastCatArchived); },
  });

  const [selectedCat, setSelectedCat] = useState<string>("");

  /**
   * 扁平类目 → 三级树。Tree 要嵌套结构，后端给的是扁平表。
   *
   * ⚠️ 刻意**不用 Tree 的 checkable**：它的勾选值只认叶子（父节点不入选中集合，
   * 见 ui/tree.tsx 的说明）—— 那是权限树的语义。这里要的是"选中任意层级看详情"，
   * 所以选中态自己持有，行右侧放一个按钮。
   */
  const tree: TreeNode[] = useMemo(() => {
    const all = cats.data ?? [];
    const build = (parentNo?: string): TreeNode[] =>
      all
        .filter((cat) => cat.parentNo === parentNo)
        .map((cat) => ({
          key: cat.categoryNo,
          label: `${cat.name}${cat.skuCount ? fill(c.catOnSale, { n: cat.skuCount }) : ""}`,
          extra: (
            <Button size="sm" variant={selectedCat === cat.categoryNo ? "secondary" : "ghost"}
              onClick={() => setSelectedCat(cat.categoryNo)}>
              {c.detail}
            </Button>
          ),
          children: build(cat.categoryNo),
        }));
    return build(undefined);
  }, [cats.data, selectedCat]);

  const pickedCat: Category | undefined = (cats.data ?? []).find((c) => c.categoryNo === selectedCat);

  const skuColumns: Column<Sku>[] = [
    { header: c.colSkuNo, cell: (s) => s.skuNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (s) => s.title.zh, className: "whitespace-normal", width: "16rem" },
    { header: c.colMerchant, cell: (s) => s.merchantName },
    { header: c.colCategory, cell: (s) => s.categoryName },
    {
      header: c.colPricing,
      // B6：缺任一市场的价格不能上架，所以列表里就要能一眼看出缺哪个
      cell: (s) => (
        <span className="flex gap-1">
          {MARKETS.map((m) =>
            s.prices[m] === undefined
              ? <Badge key={m} tone="danger">{fill(c.priceMissing, { m })}</Badge>
              : <span key={m} className="tabular-nums text-muted-foreground">{m} {money(s.prices[m]!)}</span>,
          )}
        </span>
      ),
    },
    {
      header: c.colI18n,
      // 缺译不拦上架（按 R9 回落到 zh），但要看得见 —— 否则永远没人补
      cell: (s) => {
        const missing = (["en", "ar"] as const).filter((k) => !s.title[k]);
        return missing.length
          ? <Badge tone="warning">{fill(c.i18nMissing, { langs: missing.join(" / ") })}</Badge>
          : <span className="text-muted-foreground">{c.i18nComplete}</span>;
      },
    },
    { header: c.colStock, cell: (s) => s.stock, numeric: true },
    { header: c.colStatus, cell: (s) => <SkuStatusBadge value={s.status} /> },
    {
      header: c.colActions,
      cell: (s) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(s); setReason(s.reason ?? ""); }}>
          {s.status === "PENDING" && canAudit ? c.actionAudit : c.actionView}
        </Button>
      ),
    },
  ];

  const stockRows = (presaleList.data?.records ?? []).filter((s) => s.presaleQuota > 0);
  const stockColumns: Column<Sku>[] = [
    { header: c.colSkuNo, cell: (s) => s.skuNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (s) => s.title.zh, className: "whitespace-normal", width: "16rem" },
    { header: c.colMerchant, cell: (s) => s.merchantName },
    { header: c.colPresaleQuota, cell: (s) => s.presaleQuota, numeric: true },
    {
      header: c.colSold,
      numeric: true,
      cell: (s) => (s.soldCount > s.presaleQuota ? <Badge tone="danger">{s.soldCount}</Badge> : s.soldCount),
    },
    { header: c.colCutoffAt, cell: (s) => fmtTime(s.cutoffAt) },
    { header: c.colArriveAt, cell: (s) => fmtTime(s.arriveAt) },
    {
      header: c.colActions,
      cell: (s) =>
        canEditStock ? (
          <Button size="sm" variant="outline"
            onClick={() => setPresale({ skuNo: s.skuNo, quota: String(s.presaleQuota), cutoffAt: s.cutoffAt ?? "" })}>
            {c.actionEditPresale}
          </Button>
        ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "skus" && !canAudit && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="product:sku:audit" note={c.readOnlyNote} className="mb-3" />
      )}

      {/* ── 类目树 ────────────────────────────────────────────────────── */}
      {tab === "categories" && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <Card>
            <CardHeader><CardTitle>{c.catTreeTitle}</CardTitle></CardHeader>
            <CardContent>
              <Notice className="mb-3">
                {c.catTreeNotice}
              </Notice>
              {cats.isLoading ? (
                <div className="py-8 text-center text-muted-foreground">{c.loading}</div>
              ) : (
                <Tree nodes={tree} empty={c.emptyTree} />
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle>{c.catDetailTitle}</CardTitle></CardHeader>
            <CardContent>
              {!pickedCat ? (
                <p className="txt-body text-muted-foreground">{c.catDetailHint}</p>
              ) : (
                <div>
                  <Field label={c.fieldCatNo}>{pickedCat.categoryNo}</Field>
                  <Field label={c.fieldLevel}>{fill(c.levelN, { n: pickedCat.level })}</Field>
                  <Field label={c.fieldTemplate}><StatusBadge map={templateMap} value={pickedCat.template} /></Field>
                  <Field label={c.fieldQualification}>
                    {pickedCat.qualifications.length ? (
                      <div className="space-y-1">
                        <div>{pickedCat.qualifications.join("、")}</div>
                        {/* 判据是 requiredCode 而不是上面这行文案 —— 文案给人看，编码给机器判 */}
                        <div className="txt-caption text-muted-foreground">{fill(c.requiredCode, { code: pickedCat.requiredCode ?? "—" })}</div>
                      </div>
                    ) : c.noQualification}
                  </Field>
                  <Field label={c.fieldI18nName}>
                    zh：{pickedCat.i18n.zh}
                    <br />
                    en：{pickedCat.i18n.en ?? <span className="text-[var(--warning)]">{c.i18nFallback}</span>}
                  </Field>
                  <Field label={c.fieldSkuCount}>{pickedCat.skuCount}</Field>
                  {canEditCategory && (
                    <Button
                      size="sm" variant="outline"
                      onClick={async () => {
                        const ok = await confirm({
                          title: fill(c.confirmArchiveTitle, { name: pickedCat.name }),
                          desc: c.confirmArchiveDesc,
                          danger: true, confirmText: c.confirmArchiveOk,
                        });
                        if (ok) archiveCat.mutate(pickedCat.categoryNo);
                      }}
                    >
                      {c.btnArchiveCat}
                    </Button>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* ── 商品池 ────────────────────────────────────────────────────── */}
      {tab === "skus" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSku}>
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={skuColumns} rows={skus.data?.records} loading={skus.isLoading}
            error={skus.error} onRetry={() => skus.refetch()}
            rowKey={(s) => s.skuNo}
            empty={c.emptySku}
          />
          <Pagination page={page} size={size} onSize={setSize} total={skus.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {/* ── 库存与预售 ─────────────────────────────────────────────────── */}
      {/* 商品审核：本页唯一接了真后端的一块（类目/库存仍走 mock） */}
      {tab === "audit" && <GoodsAuditTab c={c} canAudit={canAudit} />}

      {tab === "stock" && (
        <>
          <StatRow>
            <StatCard label={c.kpiPresale} value={stockRows.length} />
            <StatCard
              label={c.kpiOversell}
              value={(oversell.data ?? []).length}
              sub={(oversell.data ?? []).length > 0 ? c.kpiOversellSub : c.kpiOversellNone}
              tone={(oversell.data ?? []).length > 0 ? "down" : undefined}
            />
            <StatCard label={c.kpiOversellQty} value={(oversell.data ?? []).reduce((n, s) => n + (s.soldCount - s.presaleQuota), 0)} />
          </StatRow>
          <Notice className="mb-3">
            {c.stockNotice}
          </Notice>
          <DataTable
            columns={stockColumns} rows={stockRows} loading={presaleList.isLoading}
            error={presaleList.error} onRetry={() => presaleList.refetch()}
            rowKey={(s) => s.skuNo}
            empty={c.emptyStock}
          />
        </>
      )}

      {/* 商品详情 / 审核 */}
      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.title.zh ?? ""}
        desc={current ? `${current.skuNo} · ${current.merchantName}` : undefined}
        width="w-[560px]"
        footer={
          current && canAudit ? (
            current.status === "PENDING" ? (
              <>
                <Button variant="outline" onClick={() => audit.mutate({ skuNo: current.skuNo, pass: false, reason })}>{c.btnReject}</Button>
                <Button onClick={() => audit.mutate({ skuNo: current.skuNo, pass: true })}>{c.btnApprove}</Button>
              </>
            ) : current.status === "ON_SALE" ? (
              <Button variant="outline" onClick={() => forceOff.mutate({ skuNo: current.skuNo, reason })}>{c.btnForceOff}</Button>
            ) : null
          ) : null
        }
      >
        {current && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.colStatus}><SkuStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.colCategory}>{current.categoryName}</Field>
              <Field className="mb-3" label={c.colStock}>{current.stock}</Field>
              <Field className="mb-3" label={c.fieldCreatedAt}>{fmtTime(current.createdAt)}</Field>
            </FieldGrid>

            <Field label={c.fieldPricing}>
              <div className="space-y-1">
                {MARKETS.map((m) => (
                  <div key={m} className="flex items-center justify-between gap-3">
                    <span>{marketLabel[m]}（{m}）</span>
                    {current.prices[m] === undefined
                      ? <Badge tone="danger">{c.priceMissingBlocking}</Badge>
                      : <span className="tabular-nums">{money(current.prices[m]!)}</span>}
                  </div>
                ))}
              </div>
            </Field>

            <Field label={c.fieldI18nCopy}>
              <div className="space-y-1">
                <div>zh：{current.title.zh}</div>
                <div>en：{current.title.en ?? <span className="text-[var(--warning)]">{c.i18nFallbackLong}</span>}</div>
                <div>ar：{current.title.ar ?? <span className="text-[var(--warning)]">{c.i18nFallbackLong}</span>}</div>
              </div>
            </Field>

            {(current.status === "PENDING" || current.status === "ON_SALE") && canAudit ? (
              <Field label={current.status === "PENDING" ? c.fieldRejectReason : c.fieldForceOffReason}>
                <Textarea value={reason} onChange={setReason}
                  placeholder={c.reasonPlaceholder} />
              </Field>
            ) : (
              <Field label={c.fieldHandledReason}>{current.reason || "—"}</Field>
            )}
          </div>
        )}
      </Drawer>

      {/* 预售配置 */}
      <Drawer
        open={!!presale}
        onOpenChange={(o) => !o && setPresale(null)}
        title={c.presaleTitle}
        desc={presale?.skuNo}
        footer={presale ? <Button loading={savePresale.isPending} onClick={() => savePresale.mutate()}>{c.save}</Button> : null}
      >
        {presale && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="ps-quota" required>{c.fieldQuota}</Label>
              <Input id="ps-quota" className="w-full" value={presale.quota}
                onChange={(e) => setPresale({ ...presale, quota: e.target.value })} />
              <p className="txt-caption text-muted-foreground">{c.quotaHint}</p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="ps-cutoff" required>{c.fieldCutoff}</Label>
              <Input id="ps-cutoff" className="w-full" value={presale.cutoffAt}
                onChange={(e) => setPresale({ ...presale, cutoffAt: e.target.value })} />
              <p className="txt-caption text-muted-foreground">{c.cutoffHint}</p>
            </div>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
