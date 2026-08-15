"use client";

// 存储空间治理（TDD-图片存储与空间回收 §L3-7）—— **已接真后端** `/ops/media/**`。
//
// 这一页只做一件事：让「谁占了多少、其中多少是垃圾、垃圾怎么清掉」看得见、点得动。
//
// 三条贯穿全页的判断：
//   · 扫描只读、可以自动；删除破坏性、必须人工点头
//   · 默认一张都不勾选 —— 破坏性操作不预选
//   · 「可回收理由」是这张表的核心列，运营要靠它判断「这张能不能删」
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { MediaPurgeBatch, MediaReclaimable, MediaStoreUsage } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { FilterSelect } from "@/components/ui/filter-select";
import { Notice } from "@/components/ui/notice";
import { Pagination, Skeleton, StatCard, StatRow } from "@/components/ui/misc";
import { Drawer } from "@/components/ui/drawer";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { fmtTime } from "@/lib/utils";
import type { SystemCopy } from "./copy";

/**
 * 缩略图的源。
 *
 * <p><b>不能直接写 `/uploads/xxx`</b>：那会打到运营端自己的源上。
 * 生产走 nginx 同源反代时碰巧是对的，但跨源本地开发（ops-web 3100 / 后端 8082）
 * 就是一片裂图 —— 而缩略图恰恰是这一列存在的理由，没有它等于让人盲删。
 * 与 http-client 读同一个环境变量，同源部署时它是空串，行为不变。
 */
const MEDIA_BASE = process.env.NEXT_PUBLIC_API_BASE || "";

/** 字节数给人看。存储这一页满屏都是它，塞在各处自己拼必然不一致。 */
function mb(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

const VIEWS = ["stores", "reclaimable", "batches"] as const;
type View = (typeof VIEWS)[number];

export function StorageTab({ c, canPurge }: { c: SystemCopy; canPurge: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();

  const [view, setView] = useState<View>("stores");
  const [storeNo, setStoreNo] = useState("");
  const [includeQual, setIncludeQual] = useState(false);
  const [reason, setReason] = useState<"all" | "never" | "replaced">("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  // **默认空**：破坏性操作不预选
  const [selected, setSelected] = useState<string[]>([]);
  /** 打开明细抽屉的那个批次号。批次明细是按需拉的 —— 列表页不该为了它多一次查询。 */
  const [openBatch, setOpenBatch] = useState<string | null>(null);

  const query = {
    storeNo: storeNo || undefined,
    includeQual,
    neverUsed: reason === "all" ? undefined : reason === "never",
  };

  const overview = useQuery({ queryKey: ["media-overview"], queryFn: () => api.getMediaOverview() });
  const stores = useQuery({
    queryKey: ["media-stores"], queryFn: () => api.listMediaStoreUsage(), enabled: view === "stores",
  });
  const list = useQuery({
    queryKey: ["media-reclaimable", query, page, size],
    queryFn: () => api.listMediaReclaimable({ ...query, page, size }),
    enabled: view === "reclaimable",
  });
  /*
   * **不加 enabled**：别的页签也要知道「有没有一批正在跑」，
   * 否则运营在「待回收」页看不到警告，点下去才发现发不出。
   */
  const batches = useQuery({ queryKey: ["media-batches"], queryFn: () => api.listMediaBatches() });

  const batchDetail = useQuery({
    queryKey: ["media-batch", openBatch],
    queryFn: () => api.getMediaBatch(openBatch as string),
    enabled: openBatch != null,
  });

  /*
   * 有没有一批正在跑。**发起下一批要被它拦住** ——
   * 同一张图不会被两批领走（提交时就挂了批次号），但两批同时跑会让审计里
   * 「这次删了多少」说不清：两条 MEDIA_PURGE_DONE 交错着落下来。
   */
  const running = (batches.data ?? []).find(
    (b) => b.status === "QUEUED" || b.status === "RUNNING");

  const refreshAll = () => {
    qc.invalidateQueries({ queryKey: ["media-overview"] });
    qc.invalidateQueries({ queryKey: ["media-stores"] });
    qc.invalidateQueries({ queryKey: ["media-reclaimable"] });
    qc.invalidateQueries({ queryKey: ["media-batches"] });
  };

  const rescan = useMutation({
    mutationFn: () => api.scanMedia(),
    onSuccess: (r) => {
      refreshAll();
      setSelected([]);
      notify.success(fill(c.stScanDone, { total: r.total, marked: r.marked, rescued: r.rescued }));
    },
  });

  const backfill = useMutation({
    mutationFn: () => api.backfillMedia(),
    onSuccess: (r) => {
      refreshAll();
      notify.success(fill(c.stBackfillDone, { scanned: r.scanned, inserted: r.inserted }));
    },
  });

  const purge = useMutation({
    mutationFn: (v: { assetKeys?: string[]; expectedCount?: number }) =>
      api.purgeMedia({ ...query, ...v }),
    onSuccess: (r) => {
      refreshAll();
      setSelected([]);
      notify.success(fill(c.stPurgeSubmitted, { batchNo: r.batchNo }));
    },
  });

  const rows = list.data?.records ?? [];
  const selectedBytes = rows.filter((r) => selected.includes(r.assetKey))
    .reduce((a, r) => a + r.bytes, 0);

  /**
   * 二次确认。**照抄的是本次删除的张数**，不是固定串 ——
   * 逼着把那个数字读一遍，比抄 "DELETE" 更有信息量。
   */
  async function askAndPurge(count: number, bytes: number,
                             payload: { assetKeys?: string[]; expectedCount?: number }) {
    await confirm({
      title: c.stConfirmTitle,
      desc: fill(c.stConfirmBody, { n: count, bytes: mb(bytes) }) + "\n\n" + c.stConfirmHint,
      danger: true,
      // 照抄的是**本次删除的张数**，不是固定串 —— 逼着把那个数字读一遍
      requireText: String(count),
      /*
       * 传 action 而不是拿返回值自己 mutate：弹窗会替我们把这件事做完 ——
       * 点确认后不关闭、按钮转圈，直到落定。不传的话弹窗立刻关掉，
       * 而这段时间界面上没有任何「正在处理」的痕迹，人会再点一次那个按钮 ——
       * 对不可逆操作，再点一次意味着又发一个批次。
       */
      action: () => purge.mutateAsync(payload),
    });
  }

  const bizLabel = (t: MediaReclaimable["bizType"]) =>
    t === "QUAL" ? c.stBizQual : t === "AFTERSALE" ? c.stBizAftersale : c.stBizGoods;

  const storeLabel = (s: string) => (s === "_ENTITY" ? c.stEntityScope : s);

  const storeCols: Column<MediaStoreUsage>[] = [
    { header: c.stColStore,
      cell: (r) => (
        <div>
          <div className="txt-strong">{storeLabel(r.storeNo)}</div>
          <div className="txt-caption text-muted-foreground">
            {r.storeNo === "_ENTITY" ? c.stEntityScopeHint : r.entityNo}
          </div>
        </div>
      ),
    },
    { header: c.stColCount, cell: (r) => r.count.toLocaleString() },
    { header: c.stColActive, cell: (r) => mb(r.activeBytes) },
    {
      // 默认序就是按它倒序（后端给的）—— 这一页的目的就是找出最该清的店
      header: c.stColReclaimable,
      cell: (r) => <span className="txt-strong">{mb(r.reclaimableBytes)}</span>,
    },
    { header: c.stColRatio,
      cell: (r) => {
        const total = r.activeBytes + r.reclaimableBytes;
        return total === 0 ? "—" : `${Math.round((r.reclaimableBytes / total) * 100)}%`;
      },
    },
    { header: "",
      cell: (r) => (
        <Button variant="ghost" size="sm" onClick={() => { setStoreNo(r.storeNo); setPage(1); setView("reclaimable"); }}>
          {c.stViewReclaimable}
        </Button>
      ),
    },
  ];

  const listCols: Column<MediaReclaimable>[] = [
    { header: c.stColThumb,
      cell: (r) => (
        /*
         * 一期直接引原图 + 懒加载：运营端在内网，一页 20 张约 6 MB，可接受。
         * 切对象存储后换成 ?imageMogr2/thumbnail/160x —— 一行 URL 参数的事。
         *
         * **没有缩略图就是让人盲删**，所以这一列不能省。
         */
        <img src={`${MEDIA_BASE}/uploads/${r.assetKey}`} alt="" loading="lazy"
             className="h-12 w-12 rounded object-cover ring-1 ring-border" />
      ),
    },
    { header: c.stColBizType,
      cell: (r) => <Badge tone={r.bizType === "QUAL" ? "danger" : "muted"}>{bizLabel(r.bizType)}</Badge>,
    },
    { header: c.stColOwner,
      cell: (r) => (
        <div>
          <div>{storeLabel(r.storeNo)}</div>
          <div className="txt-caption text-muted-foreground">{r.entityNo}</div>
        </div>
      ),
    },
    { header: c.stColSize,
      cell: (r) => (
        <div>
          <div>{mb(r.bytes)}</div>
          {r.width && r.height && (
            <div className="txt-caption text-muted-foreground">{r.width}×{r.height}</div>
          )}
        </div>
      ),
    },
    { header: c.stColUploaded,
      cell: (r) => (
        <div>
          <div>{r.createdAt ? fmtTime(r.createdAt) : "—"}</div>
          <div className="txt-caption text-muted-foreground">{r.uploadedBy ?? "—"}</div>
        </div>
      ),
    },
    {
      // 整个页面最要紧的一列：运营靠它判断「这张能不能删」
      header: c.stColReason,
      cell: (r) => <span className="txt-caption">{r.reason}</span>,
    },
    { header: c.stColMarked, cell: (r) => (r.markedAt ? fmtTime(r.markedAt) : "—") },
  ];

  const batchTone = (s: MediaPurgeBatch["status"]) =>
    s === "PARTIAL" ? "danger" : s === "DONE" ? "success" : "info";
  const batchLabel = (s: MediaPurgeBatch["status"]) =>
    s === "QUEUED" ? c.stBatchQueued : s === "RUNNING" ? c.stBatchRunning
      : s === "DONE" ? c.stBatchDone : c.stBatchPartial;

  const batchCols: Column<MediaPurgeBatch>[] = [
    { header: c.stColBatchNo, cell: (r) => <span className="font-mono text-xs">{r.batchNo}</span> },
    { header: c.stColOperator,
      // 显示名是发起时的快照 —— 人离职改名之后这条记录还得说得清是谁
      cell: (r) => r.operatorName ?? r.operator,
    },
    { header: c.stColBatchStatus,
      cell: (r) => (
        <div className="flex items-center gap-2">
          <Badge tone={batchTone(r.status)}>{batchLabel(r.status)}</Badge>
          {r.status === "PARTIAL" && (
            <span className="txt-caption text-muted-foreground">{c.stBatchPartialHint}</span>
          )}
        </div>
      ),
    },
    { header: c.stColBatchCount,
      cell: (r) => `${r.purgedCount}/${r.totalCount} · ${mb(r.totalBytes)}`,
    },
    { header: c.stColBatchTime,
      cell: (r) => (
        <div className="txt-caption">
          <div>{r.startedAt ? fmtTime(r.startedAt) : "—"}</div>
          <div className="text-muted-foreground">{r.finishedAt ? fmtTime(r.finishedAt) : "—"}</div>
        </div>
      ),
    },
    { header: "",
      cell: (r) => (
        <Button variant="ghost" size="sm" onClick={() => setOpenBatch(r.batchNo)}>
          {c.stViewBatchDetail}
        </Button>
      ),
    },
  ];

  const o = overview.data;

  return (
    <div>
      {dialog}
      <Notice>{c.stNotice}</Notice>
      {/* 异常态：多半是有图片列没登记 —— 先查清楚，别照着清单删 */}
      {o?.abnormal && <Notice tone="danger">{c.stAbnormal}</Notice>}
      {running && (
        <Notice tone="warning">
          {fill(c.stRunningNotice, {
            batchNo: running.batchNo, done: running.purgedCount, total: running.totalCount,
          })}
        </Notice>
      )}
      {!canPurge && <ReadOnlyNotice what={c.stReadOnly} perm="system:media:purge" />}

      <StatRow>
        <StatCard label={c.stCardTotal} loading={overview.isLoading}
                  value={mb(o?.totalBytes ?? 0)} sub={`${o?.totalCount ?? 0}`} />
        <StatCard label={c.stCardActive} loading={overview.isLoading}
                  value={mb(o?.activeBytes ?? 0)} sub={`${o?.activeCount ?? 0}`} />
        <StatCard label={c.stCardReclaimable} loading={overview.isLoading}
                  value={mb(o?.reclaimableBytes ?? 0)} sub={`${o?.reclaimableCount ?? 0}`} tone="down" />
      </StatRow>

      <Tabs
        value={view}
        onChange={(k) => { setView(k as View); setSelected([]); }}
        tabs={[
          { key: "stores", label: c.stViewStores },
          { key: "reclaimable", label: c.stViewReclaimable },
          { key: "batches", label: c.stViewBatches },
        ]}
      />

      {view === "stores" && (
        <DataTable
          columns={storeCols} rows={stores.data} loading={stores.isLoading}
          error={stores.error} onRetry={() => stores.refetch()}
          rowKey={(r) => r.storeNo} empty={c.stEmptyStores}
        />
      )}

      {view === "reclaimable" && (
        <>
          <Toolbar
            selectedCount={selected.length}
            onClearSelection={() => setSelected([])}
            batchActions={
              <>
                <span className="txt-caption">
                  {fill(c.stSelected, { n: selected.length, bytes: mb(selectedBytes) })}
                </span>
                <Button
                  variant="outline" size="sm"
                  // 跨页全选：走筛选条件 + 预期数量，服务端比对不一致就整批拒绝
                  onClick={() => {
                    const total = list.data?.total ?? 0;
                    void askAndPurge(total, 0, { expectedCount: total });
                  }}
                >
                  {c.stSelectAllFiltered}
                </Button>
                <Button
                  variant="destructive" size="sm" disabled={o?.abnormal || purge.isPending || running != null}
                  onClick={() => void askAndPurge(selected.length, selectedBytes, { assetKeys: selected })}
                >
                  {c.stPurge}
                </Button>
              </>
            }
          >
            <FilterSelect
              value={reason} onChange={(v) => { setReason(v as typeof reason); setPage(1); }}
              options={[
                { value: "all", label: c.stReasonAll },
                { value: "never", label: c.stReasonNeverUsed },
                { value: "replaced", label: c.stReasonReplaced },
              ]}
              aria-label={c.stFilterReason}
            />
            {/* 证件默认不进清单 —— 留存期是法务口径，把这个未决状态摆在界面上 */}
            <label className="flex items-center gap-2 txt-caption" title={c.stIncludeQualHint}>
              <Checkbox checked={includeQual}
                        onChange={(v) => { setIncludeQual(v === true); setPage(1); setSelected([]); }} />
              {c.stIncludeQual}
            </label>
            <Button variant="outline" size="sm" disabled={!canPurge || rescan.isPending}
                    onClick={() => rescan.mutate()}>
              {c.stRescan}
            </Button>
            <Button variant="ghost" size="sm" disabled={!canPurge || backfill.isPending}
                    title={c.stBackfillHint} onClick={() => backfill.mutate()}>
              {c.stBackfill}
            </Button>
          </Toolbar>

          {rescan.isPending && (
            <div className="mb-3 rounded-card bg-muted/50 p-4">
              <div className="txt-caption mb-2 text-muted-foreground">{c.stScanning}</div>
              <Skeleton className="h-3 w-2/3" />
            </div>
          )}
          <DataTable
            columns={listCols} rows={rows} loading={list.isLoading || rescan.isPending}
            error={list.error} onRetry={() => list.refetch()}
            rowKey={(r) => r.assetKey}
            selectable={canPurge}
            selectedKeys={selected}
            onSelectedChange={setSelected}
            empty={c.stEmptyNoScan}
            emptyAction={canPurge ? (
              <Button variant="outline" size="sm" onClick={() => rescan.mutate()}>{c.stRescan}</Button>
            ) : undefined}
          />
          <Pagination page={page} size={size} total={list.data?.total ?? 0}
                      onPage={setPage} onSize={(s) => { setSize(s); setPage(1); }} />
        </>
      )}

      <Drawer
        open={openBatch != null} onOpenChange={(v) => setOpenBatch(v ? openBatch : null)}
        title={c.stBatchDetail} desc={c.stBatchDetailDesc} width="w-[640px]"
      >
        <DataTable
          columns={[
            { header: c.stColThumb, cell: (r: MediaReclaimable) => (
              <span className="font-mono text-[11px]">{r.assetKey.split("/").pop()}</span>
            ) },
            { header: c.stColOwner, cell: (r: MediaReclaimable) => storeLabel(r.storeNo) },
            { header: c.stColSize, cell: (r: MediaReclaimable) => mb(r.bytes) },
            { header: c.stColItemStatus, cell: (r: MediaReclaimable) => (
              // PURGED = 已删；仍是 RECLAIMABLE = 这一张失败了，重跑会接着删它
              <Badge tone={r.status === "PURGED" ? "success" : "warning"}>
                {r.status === "PURGED" ? c.stItemPurged : c.stItemPending}
              </Badge>
            ) },
          ]}
          rows={batchDetail.data?.items} loading={batchDetail.isLoading}
          error={batchDetail.error} onRetry={() => batchDetail.refetch()}
          rowKey={(r) => r.assetKey}
        />
      </Drawer>

      {view === "batches" && (
        <DataTable
          columns={batchCols} rows={batches.data} loading={batches.isLoading}
          error={batches.error} onRetry={() => batches.refetch()}
          rowKey={(r) => r.batchNo} empty={c.stEmptyBatches}
        />
      )}
    </div>
  );
}
