"use client";

// 门店档案（矩阵 P-11.2.1）。
//
// 为什么它在「商家治理」下而不是「门店主页」下：门店主页那一域管的是**门面内容**
// （店招、公告、店铺码、获客），这一页管的是**主体的下一层实体** ——
// 谁开的、在哪、按什么模式结算、钱进哪个号、还在不在营业。
// 两者读的是同一张 `mch_store`，但回答的是完全不同的问题。
//
// **只读为主**：门店资料、价格、库存运营一律不改 —— 平台的边界是「裁、定、兜」，
// 不替商家运营。唯一的写动作是解除强制下线；压下那一侧在「违规处置与封禁」，
// 因为处置动作与它的留痕必须是同一次提交。
import { useState, type ReactNode } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { usePaging } from "@/lib/use-paging";
import { money } from "@/lib/utils";
import type { StoreGovern, StoreGovernStatus } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect, TextFilter } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { HelpNote } from "@/components/ui/help-note";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { PagedTable } from "@/components/ui/paged-table";
import { IdCell } from "@/components/ui/misc";
import type { MerchantsCopy as Copy } from "./copy";
import { useCopy } from "@/lib/use-copy";
import { STORE_REVIEW_COPY } from "./operational-review-copy";
import { OperationalReview } from "./operational-review";

/**
 * 三档状态**不能压成「营业 / 停业」两档**：
 * READONLY 是商家自己关的，SUSPENDED 是平台压下的 —— 运营对这两者要做的事相反。
 */
const useStatusMap = (c: Copy, pending: string): StatusMap<StoreGovernStatus> => ({
  PENDING_OPEN: { label: pending, tone: "warning" },
  ACTIVE: { label: c.stStatusActive, tone: "success" },
  READONLY: { label: c.stStatusReadonly, tone: "muted" },
  SUSPENDED: { label: c.stStatusSuspended, tone: "danger" },
});

const MODE_OPTIONS = (c: Copy) => [
  { value: "SELF_OPERATED", label: c.stModeSelf },
  { value: "THIRD_PARTY", label: c.stModeThird },
];

export function StoresTab({ c, detailActions }: { c: Copy; detailActions?: (store: StoreGovern) => ReactNode }) {
  const qc = useQueryClient();
  const allow = useCan();
  const statusMap = useStatusMap(c, useCopy(STORE_REVIEW_COPY).pending);
  const modeOptions = MODE_OPTIONS(c);

  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [businessMode, setBusinessMode] = useState("");
  /*
   * 主体号可以由 URL 带进来（商家详情里的「看这家的门店」跳过来，P-11.2.1a）。
   * **只当初值**，之后跟着输入框走 —— 否则运营清空输入框还筛着，
   * 而页面上看起来没有任何筛选条件。
   */
  const sp = useSearchParams();
  const [merchantNo, setMerchantNo] = useState(sp.get("merchantNo") ?? "");
  const [communityNo, setCommunityNo] = useState("");
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<StoreGovern | null>(null);
  /** 二级抽屉：门店资料 / 经营审核 / 经营状况 / 覆盖与取货，一次只开一个 */
  const [panel, setPanel] = useState<"profile" | "review" | "stats" | "reach" | null>(null);
  const review = useCopy(STORE_REVIEW_COPY);

  const canBan = allow("merchant:merchant:ban");

  const q = { keyword, status, businessMode, merchantNo, page, size , communityNo: communityNo || undefined };
  const list = useQuery({ queryKey: ["stores-govern", q], queryFn: () => api.listStores(q) });

  /*
   * 社区目录（P-11.2.1b）。只取启用中的 —— 归档的小区不该还能被当筛选项，
   * 选了只会得到一份空列表而看不出原因。
   */
  const communities = useQuery({
    queryKey: ["communities", "for-store-filter"],
    queryFn: () => api.listCommunities({ page: 1, size: 200 }),
  });

  /*
   * 门店详情（P-11.2.1c）：覆盖社区 / 挂靠自提点 / 扫码数，只在打开「覆盖与取货」时取。
   */
  const detail = useQuery({
    queryKey: ["store-detail", current?.storeNo],
    queryFn: () => api.getStore(current!.storeNo),
    enabled: !!current && panel === "reach",
  });

  /*
   * 经营状况是另一个域（trade）的统计，只在打开「经营状况」时取 —— 详情摘要里不放统计。
   */
  const stats = useQuery({
    queryKey: ["store-stats", current?.storeNo],
    queryFn: () => api.getStoreStats(current!.storeNo),
    enabled: !!current && panel === "stats",
  });

  const restore = useMutation({
    mutationFn: (storeNo: string) => api.restoreStore(storeNo),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ["stores-govern"] });
      // 门店被压下时货架也被撤了，恢复后商品池那边的投影跟着变
      qc.invalidateQueries({ queryKey: ["goods-pool"] });
      setCurrent(s);
      notify.success(c.stToastRestored);
    },
  });

  const columns: Column<StoreGovern>[] = [
    { header: c.stColNo, cell: (s) => <IdCell value={s.storeNo} />, numeric: true, align: "start" },
    {
      header: c.stColName,
      cell: (s) => (
        <span className="flex items-center gap-2">
          {s.name}
          {s.isDefault && <Badge tone="muted">{c.stIsDefault}</Badge>}
        </span>
      ),
    },
    { header: c.stColMerchant, cell: (s) => s.merchantName },
    { header: c.stColStatus, cell: (s) => <StatusBadge map={statusMap} value={s.status} /> },
    {
      header: c.stColMode,
      cell: (s) => (s.businessMode === "SELF_OPERATED"
        ? <Badge tone="warning">{c.stModeSelf}</Badge>
        : <Badge tone="success">{c.stModeThird}</Badge>),
    },
    {
      // 空**不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者，
      // 然后有人去给这家店补一个它本来就不需要的收款号
      header: c.stColPayMerchant,
      cell: (s) => s.payMerchantNo ?? <span className="text-muted-foreground">{c.stPayFallback}</span>,
    },
    {
      header: c.stColActions,
      cell: (s) => <Button size="sm" variant="outline" onClick={() => setCurrent(s)}>{c.stDetail}</Button>,
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.stNotice}</HelpNote>

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.stSearchPh}>
        {/*
          用 <TextFilter> 而不是裸 <Input>：这个值直接进 queryKey，
          裸 <Input> 会每敲一个字符发一次请求，而且它的选中态不进筛选回显 ——
          旁边三个 <FilterSelect> 都出 chip，只有这一个不出，最容易被当成「我没筛」。
        */}
        <TextFilter
          className="w-52" aria-label={c.stFilterMerchant} placeholder={c.stFilterMerchantPh}
          value={merchantNo} onChange={(v) => { setMerchantNo(v); setPage(1); }}
        />
        <FilterSelect
          aria-label={c.stFilterStatus} value={status} allLabel={c.stFilterStatusAll}
          options={statusMap} onChange={(v) => { setStatus(v); setPage(1); }}
        />
        <FilterSelect
          aria-label={c.stFilterMode} value={businessMode} allLabel={c.stFilterModeAll}
          options={modeOptions} onChange={(v) => { setBusinessMode(v); setPage(1); }}
        />
        {/* BD 的问题是「这个片区有哪些店」，片区就是他跑的单位 */}
        <FilterSelect
          aria-label={c.stFilterCommunity} value={communityNo} allLabel={c.stFilterCommunityAll}
          options={(communities.data?.records ?? []).map((x) => ({ value: x.communityNo, label: x.name }))}
          onChange={(v) => { setCommunityNo(v); setPage(1); }}
        />
      </Toolbar>

      <PagedTable
        query={list}
        page={page}
        size={size}
        onPage={setPage}
        onSize={setSize}
        loading={list.isLoading}
        columns={columns}
        rowKey={(s) => s.storeNo}
        empty={c.stEmpty}
      />

      <Drawer
        open={!!current}
        onOpenChange={(o) => { if (!o) { setCurrent(null); setPanel(null); } }}
        title={current?.name ?? ""}
        desc={current ? `${current.storeNo} · ${current.merchantName}` : undefined}
        width="w-[560px]"
      >
        {current && (
          <div>
            {/* 主抽屉只放摘要与入口；资料、经营数、覆盖、审核各开一个二级抽屉，一屏不堆满 */}
            <DrawerSection first title={c.stSecProfile}>
              <FieldGrid>
                <Field className="mb-3" label={c.stColStatus}><StatusBadge map={statusMap} value={current.status} /></Field>
                <Field className="mb-3" label={c.stColMode}>
                  {current.businessMode === "SELF_OPERATED" ? c.stModeSelf : c.stModeThird}
                </Field>
                <Field className="mb-3" label={c.stColMerchant}>{current.merchantName}</Field>
                <Field className="mb-3" label={c.stColPayMerchant}>
                  {current.payMerchantNo ?? <span className="text-muted-foreground">{c.stPayFallback}</span>}
                </Field>
              </FieldGrid>
              <Field label={c.stFieldAddress}>{current.address}</Field>
            </DrawerSection>

            <DrawerSection title={c.stSecActions}>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="outline" onClick={() => setPanel("profile")}>{c.stSecProfile}</Button>
                <Button size="sm" variant="outline" onClick={() => setPanel("review")}>{review.title}</Button>
                <Button size="sm" variant="outline" onClick={() => setPanel("stats")}>{c.stSecStats}</Button>
                <Button size="sm" variant="outline" onClick={() => setPanel("reach")}>{c.stSecReach}</Button>
              </div>
              <div className="mt-2 flex flex-wrap gap-2">
                <Button size="sm" variant="ghost" asChild>
                  <Link href={`/orders?storeNo=${encodeURIComponent(current.storeNo)}`} data-audit-skip="button-aschild">{c.stViewOrders}</Link>
                </Button>
                <Button size="sm" variant="ghost" asChild>
                  <Link href={`/products?tab=skus&storeNo=${encodeURIComponent(current.storeNo)}`} data-audit-skip="button-aschild">{c.stViewGoods}</Link>
                </Button>
              </div>
              {detailActions && <div className="mt-2">{detailActions(current)}</div>}
            </DrawerSection>

            {/* 解除强制下线只对平台压下的店出现；其余状态不占位 */}
            {current.status === "SUSPENDED" && (
              <DrawerSection title={c.stBtnRestore} desc={c.stRestoreHint}>
                {canBan
                  ? <Button loading={restore.isPending} onClick={() => restore.mutate(current.storeNo)}>{c.stBtnRestore}</Button>
                  : <ReadOnlyNotice what={c.stReadOnlyWhat} perm="merchant:merchant:ban" />}
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>

      <Drawer open={!!current && panel === "profile"} onOpenChange={(o) => !o && setPanel(null)} title={c.stSecProfile} desc={current?.name}>
        {current && (
          <DrawerSection first title={c.stSecProfile}>
            <Field label={c.stFieldOpenHours}>{current.openHours}</Field>
            <Field label={c.stFieldAnnouncement}>
              {current.announcement || <span className="text-muted-foreground">{c.stAnnouncementEmpty}</span>}
            </Field>
            {/* 配送四个数摆在一起：单看任何一个都判断不了这家店的配送划不划得来 */}
            <FieldGrid>
              <Field className="mb-3" label={c.stFieldRadius}>{fill(c.stMeters, { n: current.deliveryRadiusM })}</Field>
              <Field className="mb-3" label={c.stFieldMinOrder}>{money(current.deliveryMinOrderMinor)}</Field>
              <Field className="mb-3" label={c.stFieldFee}>{money(current.deliveryFeeMinor)}</Field>
              <Field className="mb-3" label={c.stFieldFreeThreshold}>{money(current.deliveryFreeThresholdMinor)}</Field>
            </FieldGrid>
          </DrawerSection>
        )}
      </Drawer>

      <Drawer open={!!current && panel === "review"} onOpenChange={(o) => !o && setPanel(null)} title={review.title} desc={current?.name}>
        {current && <OperationalReview key={current.storeNo} storeNo={current.storeNo} onChanged={(r) => setCurrent((store) => store?.storeNo === r.storeNo ? { ...store, status: r.status } : store)} />}
      </Drawer>

      <Drawer open={!!current && panel === "stats"} onOpenChange={(o) => !o && setPanel(null)} title={c.stSecStats} desc={current?.name}>
        <DrawerSection first title={c.stSecStats}>
          {stats.isLoading && <p className="txt-caption text-muted-foreground">{c.stStatsLoading}</p>}
          {stats.data && (
            <FieldGrid>
              <Field className="mb-3" label={c.stStatTodayOrders}>{stats.data.todayOrders}</Field>
              <Field className="mb-3" label={c.stStatTodayGmv}>{money(stats.data.todayGmvMinor)}</Field>
              <Field className="mb-3" label={c.stStatMonthOrders}>{stats.data.monthOrders}</Field>
              <Field className="mb-3" label={c.stStatMonthGmv}>{money(stats.data.monthGmvMinor)}</Field>
              {/* 自带客流占比直接对应这家店少付的佣金（ADR-004），所以与经营数摆在一起 */}
              <Field className="mb-3" label={c.stStatOwnedTraffic}>{Math.round(stats.data.ownedTrafficRate * 100)}%</Field>
              <Field className="mb-3" label={c.stStatToShip}>{stats.data.toShip}</Field>
              <Field className="mb-3" label={c.stStatToDeliver}>{stats.data.toDeliver}</Field>
              <Field className="mb-3" label={c.stStatToStock}>{stats.data.toStock}</Field>
              <Field className="mb-3" label={c.stStatToAfterSale}>{stats.data.toAfterSale}</Field>
            </FieldGrid>
          )}
        </DrawerSection>
      </Drawer>

      <Drawer open={!!current && panel === "reach"} onOpenChange={(o) => !o && setPanel(null)} title={c.stSecReach} desc={current?.name}>
        {current && (
          <DrawerSection first title={c.stSecReach} desc={c.stReachHint}>
              <FieldGrid>
                {/*
                  ⚠️ 这一栏此前读的是 mch_entity_community，而那张表线上是 **0 行**
                  （经营范围早就搬到 mch_service_area 了）—— 于是它对平台上
                  **每一家商家**都显示「未覆盖任何社区」。不是报错、不是空白，
                  是一行确定的「没有」：运营据此判断「这家还没配范围」，
                  而他配了，只是配在另一张表里。

                  现在三块分开：框了什么 / 排除了什么 / 实际覆盖到哪儿。
                */}
                <Field className="mb-3" label={c.stFieldCommunities}>
                  {detail.data?.coverage?.includes?.length
                    ? detail.data.coverage!.includes.map((a) => a.name).join("、")
                    : <span className="text-muted-foreground">{c.stNoCommunity}</span>}
                </Field>
                {/*
                  排除项**单列一栏**。混进上面那一栏，运营会读成「他做这儿」，
                  而事实正好相反 —— 一个字段读反比没有这个字段更糟。
                  没有排除项时整栏不出现：留一个空栏会让人以为「这里还没查出来」。
                */}
                {!!detail.data?.coverage?.excludes?.length && (
                  <Field className="mb-3" label={c.stFieldExcludes}>
                    <span className="text-destructive">
                      {detail.data.coverage!.excludes.map((a) => a.name).join("、")}
                    </span>
                  </Field>
                )}
                {/*
                  ⚠️ 每一处都 `coverage?.`：接**还没发这个字段的后端**时（部署有先后），
                  少一栏是「少一栏」，而 `coverage.includes` 直接抛 TypeError ——
                  整个抽屉白屏，运营连门店档案都打不开了。浏览器上当场撞到这个。

                  投影结果：**框了什么 ≠ 覆盖到什么**。框一个街道可能展开成 30 个聚落，
                  也可能一个都没有（那条街道下还没开通任何聚落）—— 后者在只看
                  「他框了什么」的界面上完全看不出来，而买家看到的是后者。
                */}
                <Field className="mb-3" label={c.stFieldReachable}>
                  {detail.data?.coverage ? (
                    detail.data.coverage.reachableCount === 0 ? (
                      <span className="text-destructive">{c.stReachableZero}</span>
                    ) : (
                      <>
                        <span className="tabular-nums">{fill(c.stReachableCount, { n: detail.data.coverage.reachableCount })}</span>
                        {!!detail.data.coverage.reachableSample.length && (
                          <span className="ml-2 txt-caption text-muted-foreground">
                            {detail.data.coverage.reachableSample.join("、")}
                            {detail.data.coverage.reachableCount > detail.data.coverage.reachableSample.length ? " …" : ""}
                          </span>
                        )}
                      </>
                    )
                  ) : "—"}
                </Field>
                <Field className="mb-3" label={c.stFieldPickups}>
                  {/* 空 = 没挂，不是没查到 —— 两者在界面上要分得开 */}
                  {detail.data?.pickupNames.length
                    ? detail.data.pickupNames.join("、")
                    : <span className="text-muted-foreground">{c.stNoPickup}</span>}
                </Field>
                <Field className="mb-3" label={c.stFieldScan30d}>
                  {detail.data ? detail.data.scanCount30d : "—"}
                </Field>
                <Field className="mb-3" label={c.stFieldRating}>
                  {/*
                    ★ 按**条数**判空，不按分值：ratingCount=0 是「暂无评价」，
                    按分值判会把「没人评过」显示成「0 分」。rating 是 ×10 的整数。
                  */}
                  {current.ratingCount
                    ? `${((current.rating ?? 0) / 10).toFixed(1)}（${current.ratingCount}）`
                    : <span className="text-muted-foreground">{c.stNoRating}</span>}
                </Field>
              </FieldGrid>
          </DrawerSection>
        )}
      </Drawer>
    </>
  );
}
