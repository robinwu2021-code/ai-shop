"use client";

// 库存对差 —— 接 `/ops/inventory/recon`（平台侧 vs 进销存侧，逐条比）。
//
// **这一页是 G3 的判据**：真相源从 `prd_sku.stock` 切到 `inv_stock_balance` 之前，
// 它必须连续读到「干净」。直接切等于从切换那天开始超卖，而且无从回溯是从哪一刻起的。
//
// 所以这里最重要的不是表格，是顶上那个结论：**「有差异」和「还没跑」必须长得不一样**。
// 两者都是「没看到差异行」，但一个是可以切、一个是不知道能不能切。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InvReconDiff } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { InventoryCopy } from "./copy";

export function ReconTab({ c }: { c: InventoryCopy }) {
  const recon = useQuery({ queryKey: ["inv-recon"], queryFn: () => api.getInvRecon() });
  const r = recon.data;

  const columns: Column<InvReconDiff>[] = [
    {
      header: c.invColMerchant,
      cell: (d) => (
        <div>
          <div>{d.entityNo}</div>
          {d.storeNo && <div className="text-xs text-muted-foreground">{d.storeNo}</div>}
        </div>
      ),
    },
    { header: c.invColSku, cell: (d) => <span className="tabular-nums">{d.skuNo}</span> },
    { header: c.invColPlatform, numeric: true, cell: (d) => d.platformQty },
    { header: c.invColInventory, numeric: true, cell: (d) => d.inventoryQty },
    {
      header: c.invColGap,
      numeric: true,
      cell: (d) => {
        const gap = d.inventoryQty - d.platformQty;
        return <span className="font-semibold text-destructive">{gap > 0 ? `+${gap}` : gap}</span>;
      },
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.invReconNotice}</Notice>

      {/*
        结论横幅。**只在数据到手后画** —— 加载中画一个灰底的「干净」，
        会让人在接口还没回来的时候就以为可以切了。
      */}
      {r && (
        <Notice tone={r.clean ? "info" : "danger"}>
          <span className="font-semibold">{r.clean ? c.invReconClean : c.invReconDirty}</span>
          {"　"}
          <span className="tabular-nums">
            {c.invReconScanned} {r.scannedSkus} · {c.invReconSkipped} {r.skipped} ·{" "}
            {c.invReconDiffs} {r.diffs.length}
          </span>
        </Notice>
      )}

      {r && !r.clean && <Notice tone="warning">{c.invReconHowTo}</Notice>}

      <DataTable
        columns={columns}
        rows={r?.diffs}
        loading={recon.isLoading}
        error={recon.error}
        onRetry={() => recon.refetch()}
        empty={c.invReconNoDiff}
        rowKey={(d) => `${d.entityNo}-${d.skuNo}`}
      />
    </div>
  );
}
