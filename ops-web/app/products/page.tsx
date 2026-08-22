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
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime, money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { MARKETS, type Category, type Market, type ProductGoods, type Sku } from "@/lib/types";
import { SkuStatusBadge, useCategoryTemplateMap, useSkuStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { GoodsAuditTab } from "./goods-audit-tab";
import { SpecTemplateTab } from "./spec-template-tab";
import { CategoriesTab } from "./categories-tab";
import { SpuStdTab } from "./spu-std-tab";
import { TopicsTab } from "./topics-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
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
const TAB_KEYS = ["categories", "skus", "audit", "stock", "templates", "spu-std", "topics"] as const;   // 顺序与 lib/nav.ts 的叶子一致

const MARKET_LABEL = (c: Copy): Record<Market, string> => ({ CN: c.marketCN, SG: c.marketSG });

export default function ProductsPage() {
  return <Suspense fallback={null}><ProductsInner /></Suspense>;
}

function ProductsInner() {
  const c = useCopy(PRODUCTS_COPY);
  const tabs = useNavTabs("/products", TAB_KEYS);
  const marketLabel = MARKET_LABEL(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => {
    setPage(1); setKeyword(""); setStatus(""); setMerchantFilter(""); setCategoryFilter("");
  });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  /** 商品池按商家/类目筛选（P-3.2）。两个维度都是 mock 早就支持的过滤条件，此前只是没接 UI。 */
  const [merchantFilter, setMerchantFilter] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [current, setCurrent] = useState<ProductGoods | null>(null);
  /** 抽屉里当前在操作哪个 sku——一个商品可能有多个规格，审核/强制下架是打在具体某个 sku 上的 */
  const [activeSkuNo, setActiveSkuNo] = useState<string | null>(null);
  /**
   * 抽屉里正在做 **goods 级**强制下架（= 撤销过审）。
   *
   * 与 `activeSkuNo` 互斥而不是共用一个状态：两者粒度不同 ——
   * 一个撤的是整件商品的过审结论（商家必须改完重新提审），
   * 一个只压一个规格。共用一个状态会写出「选了规格却撤了整件」这种歧义。
   */
  const [goodsOff, setGoodsOff] = useState(false);
  const [reason, setReason] = useState("");
  const [presale, setPresale] = useState<{ skuNo: string; quota: string; cutoffAt: string; arriveAt: string } | null>(null);

  const canAudit = allow("product:sku:audit");
  const canEditCategory = allow("product:category:update");
  const canEditStock = allow("product:stock:update");

  const templateMap = useCategoryTemplateMap();
  const statusMap = useSkuStatusMap();

  // 商品池的商家/类目筛选也要用到这两份列表，不再只在「类目」tab 下拉取
  const cats = useQuery({ queryKey: ["categories"], queryFn: () => api.listCategories(), enabled: tab === "categories" || tab === "skus" });
  const merchantsForFilter = useQuery({
    queryKey: ["merchants", "lite"],
    queryFn: () => api.listMerchants({ size: 200 }),
    enabled: tab === "skus",
  });
  const goodsQ = { keyword, status, merchantNo: merchantFilter, categoryNo: categoryFilter, page, size };
  const goodsList = useQuery({ queryKey: ["goods-pool", goodsQ], queryFn: () => api.listGoods(goodsQ), enabled: tab === "skus" });
  const oversell = useQuery({ queryKey: ["oversell"], queryFn: () => api.listOversellSkus(), enabled: tab === "stock" });
  /*
   * 预售清单**由后端筛**（`presaleOnly`），不再拉一页回来自己 filter。
   *
   * 此前是 `listSkus({ size: 100 })` 再在下面 `filter(presaleQuota > 0)` ——
   * mock 上永远对（样本只有八条），接上真库之后预售 SKU 大概率不在前 100 条里，
   * 这个 tab 会长期显示为空，而接口 200、数据也是真的。
   */
  const presaleList = useQuery({
    queryKey: ["skus", "presale", page, size],
    queryFn: () => api.listSkus({ presaleOnly: true, page, size }),
    enabled: tab === "stock",
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["skus"] });
    qc.invalidateQueries({ queryKey: ["goods-pool"] });
    qc.invalidateQueries({ queryKey: ["oversell"] });
    qc.invalidateQueries({ queryKey: ["categories"] });
  };

  const audit = useMutation({
    mutationFn: (v: { skuNo: string; pass: boolean; reason?: string }) => api.auditSku(v.skuNo, v.pass, v.reason),
    onSuccess: (s) => {
      invalidate(); setCurrent(null); setActiveSkuNo(null); setReason("");
      notify.success(s.status === "ON_SALE" ? c.toastApproved : c.toastRejected);
    },
  });
  const forceOff = useMutation({
    mutationFn: (v: { skuNo: string; reason: string }) => api.forceOffSku(v.skuNo, v.reason),
    onSuccess: () => { invalidate(); setCurrent(null); setActiveSkuNo(null); setReason(""); notify.success(c.toastForcedOff); },
  });
  /*
   * goods 级强制下架 —— **已接真后端** `POST /ops/goods/{goodsNo}/force-off`。
   *
   * 与上面那个 sku 级的 forceOff 的差别**不在作用范围**（两者都作用于整件商品，
   * 后端把 skuNo 解析到父商品再执行），而在**撤不撤过审**：
   *   - 这个：撤销过审 → 商品回到 REJECTED，商家必须改完重新提审
   *   - sku 级：只压下架、保留过审结论 → 商家处理完自己点一下就能回来
   *
   * 上一版注释写的是「那个只是把一个规格下架」—— 那是照着 mock 层那台
   * 不存在的 SKU 状态机写的，真库 `prd_sku` 没有状态列。
   */
  const forceOffGoods = useMutation({
    mutationFn: () => api.forceOffGoods(current!.goodsNo, reason),
    onSuccess: () => {
      invalidate(); setCurrent(null); setGoodsOff(false); setReason("");
      notify.success(c.toastGoodsForcedOff);
    },
  });
  const savePresale = useMutation({
    // arriveAt 传空 = 不改（后端语义）——「不改」与「清空」必须分开：
    // 只改额度的那次提交若把它抹掉，「截单必须早于到货」这条校验从此形同虚设
    mutationFn: () => api.setSkuPresale(presale!.skuNo, Number(presale!.quota), presale!.cutoffAt, presale!.arriveAt || undefined),
    onSuccess: () => { invalidate(); setPresale(null); notify.success(c.toastPresaleSaved); },
  });
  /**
   * 类目编辑表单。`null` = 抽屉关着。
   *
   * <p>此前这一页**只能看与归档** —— 新开一个类目要等一条迁移，
   * 而门槛码（经营准入的判据）连契约里都没有，运营在界面上根本设不了。
   */
  const [catForm, setCatForm] = useState<{
    categoryNo?: string; name: string; i18nEn: string; parentNo: string;
    template: string; requiredCode: string; sort: string;
  } | null>(null);

  // 授权码字典：门槛码要从这里挑，不能手输 —— 输错一个字母就是一个永不命中的门槛
  const authCodes = useQuery({
    queryKey: ["auth-code-dict"],
    queryFn: () => api.listAuthCodeDict(),
    enabled: tab === "categories",
  });

  const saveCat = useMutation({
    mutationFn: () =>
      api.saveCategory({
        categoryNo: catForm!.categoryNo,
        name: catForm!.name.trim(),
        i18nEn: catForm!.i18nEn.trim() || undefined,
        parentNo: catForm!.parentNo || undefined,
        template: catForm!.template,
        qualifications: [],
        // 空串 = 无门槛，与「没传」是同一件事
        requiredCode: catForm!.requiredCode || undefined,
        sort: Number(catForm!.sort) || 0,
      } as Parameters<typeof api.saveCategory>[0]),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["categories"] });
      setCatForm(null);
      notify.success(c.catSaved);
    },
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
    /*
     * 顶层用「没有父」判定，而不是 `=== undefined`：
     * 后端下发的一级类目 parentNo 是 **null**，而这里传的是 undefined ——
     * 两者严格不等，于是一个根节点都匹配不到，页面显示「还没有类目」，
     * 而接口明明返回了 10 条、状态码 200。
     */
    const build = (parentNo?: string): TreeNode[] =>
      all
        .filter((cat) => (parentNo ? cat.parentNo === parentNo : !cat.parentNo))
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

  const merchantOptions = (merchantsForFilter.data?.records ?? []).map((m) => ({ value: m.merchantNo, label: m.name }));
  /**
   * 类目筛选项拍平成一层，带父类目面包屑——三级树摊平成下拉选不了「选中任意一层」，
   * 而运营真正想筛的往往是叶子类目（如"叶菜"），面包屑让人不用记编号也能认出是哪条枝。
   */
  const categoryOptions = useMemo(() => {
    const all = (cats.data ?? []).filter((cat) => !cat.archivedAt);
    const nameOf = (no?: string) => all.find((x) => x.categoryNo === no)?.name;
    return all.map((cat) => ({
      value: cat.categoryNo,
      label: cat.parentNo ? `${nameOf(cat.parentNo)} / ${cat.name}` : cat.name,
    }));
  }, [cats.data]);

  const goodsColumns: Column<ProductGoods>[] = [
    { header: c.colSkuNo, cell: (g) => g.goodsNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (g) => g.title.zh, className: "whitespace-normal", width: "16rem" },
    { header: c.colMerchant, cell: (g) => g.merchantName },
    { header: c.colCategory, cell: (g) => g.categoryName ?? <span className="text-muted-foreground">—</span> },
    {
      // 一个商品可能有好几个规格，列表只给"起价"——每个市场取全部 sku 里最低的那个价
      // B6：缺任一市场的价格不能上架，所以要能一眼看出缺哪个（只要有一个 sku 缺，这里就标出来）
      header: c.colPricing,
      cell: (g) => (
        <span className="flex gap-1">
          {MARKETS.map((m) => {
            const prices = g.skus.map((s) => s.prices[m]).filter((p): p is number => p !== undefined);
            return prices.length === 0
              ? <Badge key={m} tone="danger">{fill(c.priceMissing, { m })}</Badge>
              : <span key={m} className="tabular-nums text-muted-foreground">{m} {fill(c.priceFrom, { v: money(Math.min(...prices)) })}</span>;
          })}
        </span>
      ),
    },
    {
      header: c.colI18n,
      // 缺译不拦上架（按 R9 回落到 zh），但要看得见 —— 否则永远没人补
      cell: (g) => {
        const missing = (["en", "ar"] as const).filter((k) => !g.title[k]);
        return missing.length
          ? <Badge tone="warning">{fill(c.i18nMissing, { langs: missing.join(" / ") })}</Badge>
          : <span className="text-muted-foreground">{c.i18nComplete}</span>;
      },
    },
    { header: c.colSkuCount, cell: (g) => g.skus.length, numeric: true },
    { header: c.colStatus, cell: (g) => <SkuStatusBadge value={g.status as Sku["status"]} /> },
    {
      header: c.colActions,
      cell: (g) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(g); setActiveSkuNo(null); setReason(""); }}>
          {g.status === "PENDING" && canAudit ? c.actionAudit : c.actionView}
        </Button>
      ),
    },
  ];

  // 后端已按 presaleOnly 筛过，这里不再二次过滤 —— 再筛一次会让分页总数与行数对不上
  const stockRows = presaleList.data?.records ?? [];
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
            onClick={() => setPresale({ skuNo: s.skuNo, quota: String(s.presaleQuota), cutoffAt: s.cutoffAt ?? "", arriveAt: s.arriveAt ?? "" })}>
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
      {tab === "categories" && <CategoriesTab c={c} canEdit={canEditCategory} />}

      {/* ── 商品池 ────────────────────────────────────────────────────── */}
      {tab === "skus" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSku}>
            <FilterSelect aria-label={c.filterMerchant} value={merchantFilter} onChange={(v) => { setMerchantFilter(v); setPage(1); }} options={merchantOptions} allLabel={c.filterMerchantAll} />
            <FilterSelect aria-label={c.filterCategory} value={categoryFilter} onChange={(v) => { setCategoryFilter(v); setPage(1); }} options={categoryOptions} allLabel={c.filterCategoryAll} />
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={goodsColumns} rows={goodsList.data?.records} loading={goodsList.isLoading}
            error={goodsList.error} onRetry={() => goodsList.refetch()}
            rowKey={(g) => g.goodsNo}
            empty={c.emptySku}
          />
          <Pagination page={page} size={size} onSize={setSize} total={goodsList.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {/* ── 库存与预售 ─────────────────────────────────────────────────── */}
      {/* 商品审核：本页唯一接了真后端的一块（类目/库存仍走 mock） */}
      {tab === "audit" && <GoodsAuditTab c={c} canAudit={canAudit} />}
      {/* 标准品库：后端 V166 就通了，缺的一直是运营录入这一步 —— 没有它整个功能是锁着的 */}
      {tab === "spu-std" && <SpuStdTab c={c} canEdit={allow("product:std:update")} />}

      {tab === "topics" && <TopicsTab c={c} canEdit={allow("product:topic:update")} />}

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
          <Pagination page={page} size={size} onSize={setSize} total={presaleList.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {/* 规格模板（P-3.4 / E27）：平台侧终于有了维护入口 —— B-4.4 商家选到的不再是一张空表 */}
      {tab === "templates" && <SpecTemplateTab c={c} canEdit={canEditCategory} />}

      {/* 商品详情 / 审核。一个商品可能有好几个规格，通过/驳回/强制下架都是打在具体某个 sku 上的 */}
      <Drawer
        open={!!current}
        onOpenChange={(o) => { if (!o) { setCurrent(null); setGoodsOff(false); setActiveSkuNo(null); setReason(""); } }}
        title={current?.title.zh ?? ""}
        desc={current ? `${current.goodsNo} · ${current.merchantName}` : undefined}
        width="w-[560px]"
        footer={
          current && canAudit && goodsOff ? (
            <Button
              loading={forceOffGoods.isPending}
              // 原因原样进商家 B 端 —— 空原因等于让商家猜，猜不到就会反复重提
              disabled={!reason.trim()}
              onClick={() => forceOffGoods.mutate()}
            >
              {c.btnConfirmForceOffGoods}
            </Button>
          ) : current && canAudit && activeSkuNo ? (
            current.status === "PENDING" ? (
              <Button onClick={() => audit.mutate({ skuNo: activeSkuNo, pass: false, reason })}>{c.btnConfirmReject}</Button>
            ) : current.status === "ON_SALE" ? (
              <Button onClick={() => forceOff.mutate({ skuNo: activeSkuNo, reason })}>{c.btnConfirmForceOff}</Button>
            ) : null
          ) : null
        }
      >
        {current && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.colStatus}><SkuStatusBadge value={current.status as Sku["status"]} /></Field>
              <Field className="mb-3" label={c.colCategory}>{current.categoryName ?? "—"}</Field>
            </FieldGrid>

            <Field label={c.fieldI18nCopy}>
              <div className="space-y-1">
                <div>zh：{current.title.zh}</div>
                <div>en：{current.title.en ?? <span className="text-[var(--warning)]">{c.i18nFallbackLong}</span>}</div>
                <div>ar：{current.title.ar ?? <span className="text-[var(--warning)]">{c.i18nFallbackLong}</span>}</div>
              </div>
            </Field>

            {/* goods 级动作摆在规格清单**之前**：它撤的是整件商品的过审结论，
                摆在一堆规格按钮中间会被当成"对某个规格的操作" */}
            {canAudit && current.status === "ON_SALE" && (
              <Field label={c.fieldGoodsAction}>
                <Button
                  size="sm" variant="outline"
                  onClick={() => { setGoodsOff(true); setActiveSkuNo(null); setReason(""); }}
                >
                  {c.btnForceOffGoods}
                </Button>
                <p className="mt-1 txt-caption text-muted-foreground">{c.forceOffGoodsHint}</p>
              </Field>
            )}

            <Field label={c.fieldSkuList}>
              {/*
                作用域必须写在动作旁边。运营在这一栏里对某一行点驳回，
                后端解析到父商品再执行 —— 整件商品连同其他规格一起被打回，
                而这一栏长得像「逐规格管理」。不说出来就是让人以为自己只压了一个规格。
              */}
              <p className="txt-caption text-muted-foreground mb-2">{c.skuScopeWarn}</p>
              <div className="space-y-2">
                {current.skus.map((s) => (
                  <div key={s.skuNo} className="rounded-field border p-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="txt-caption text-muted-foreground">
                        {s.skuNo}{s.spec ? ` · ${s.spec}` : ""} · {c.colStock} {s.stock}
                      </span>
                      <span className="flex gap-1">
                        {MARKETS.map((m) => s.prices[m] === undefined
                          ? <Badge key={m} tone="danger">{fill(c.priceMissing, { m })}</Badge>
                          : <span key={m} className="tabular-nums text-muted-foreground">{m} {money(s.prices[m]!)}</span>)}
                      </span>
                    </div>
                    {canAudit && current.status === "PENDING" && (
                      <div className="flex gap-2 mt-2">
                        <Button size="sm" onClick={() => audit.mutate({ skuNo: s.skuNo, pass: true })}>{c.btnApprove}</Button>
                        <Button size="sm" variant="outline"
                          onClick={() => { setActiveSkuNo(s.skuNo); setReason(""); }}>
                          {c.btnReject}
                        </Button>
                      </div>
                    )}
                    {canAudit && current.status === "ON_SALE" && (
                      <div className="mt-2">
                        <Button size="sm" variant="outline"
                          onClick={() => { setActiveSkuNo(s.skuNo); setReason(""); }}>
                          {c.btnForceOff}
                        </Button>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </Field>

            {goodsOff && (
              <Field label={c.fieldGoodsForceOffReason}>
                <Textarea value={reason} onChange={setReason} placeholder={c.reasonPlaceholder} />
              </Field>
            )}

            {activeSkuNo && !goodsOff && (
              <Field label={current.status === "PENDING" ? c.fieldRejectReason : c.fieldForceOffReason}>
                <Textarea value={reason} onChange={setReason} placeholder={c.reasonPlaceholder} />
              </Field>
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
            {/* 到货时间此前只在列表里显示、没有任何地方能填 —— 那一列因此永远是空的，
                而「截单必须早于到货」这条校验也就永远校验不到东西 */}
            <div className="space-y-1">
              <Label htmlFor="ps-arrive">{c.fieldArrive}</Label>
              <Input id="ps-arrive" className="w-full" value={presale.arriveAt}
                onChange={(e) => setPresale({ ...presale, arriveAt: e.target.value })} />
              <p className="txt-caption text-muted-foreground">{c.arriveHint}</p>
            </div>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
