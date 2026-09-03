"use client";

// 库存对差 —— 接 `/ops/inventory/recon`（平台侧 vs 进销存侧，逐条比）。
//
// **这一页是 G3 的判据**：真相源从 `prd_sku.stock` 切到 `inv_stock_balance` 之前，
// 它必须连续读到「干净」。直接切等于从切换那天开始超卖，而且无从回溯是从哪一刻起的。
//
// 所以这里最重要的不是表格，是顶上那个结论：**「有差异」和「还没跑」必须长得不一样**。
// 两者都是「没看到差异行」，但一个是可以切、一个是不知道能不能切。
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import type { InvReconDiff } from "@/lib/types";
import { cleanStreak } from "@/lib/recon-streak";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import type { InventoryCopy } from "./copy";

export function ReconTab({ c }: { c: InventoryCopy }) {
  const recon = useQuery({ queryKey: ["inv-recon"], queryFn: () => api.getInvRecon() });
  const r = recon.data;
  const allow = useCan();
  const canRepair = allow("inventory:projection:repair");
  const { confirm, dialog } = useConfirm();

  /*
   * 补投影。**两步**：先试算（默认），把「会搬多少」摆给人看，再确认真搬。
   * 一步到位的话，第一个点它的人就已经写了库 —— 而 dry-run 的默认值
   * 本来就是一个决定，不该被一个按钮绕过去。
   */
  /*
   * 对差趋势（M7）。**判据是「连续 N 天为零」，而这一页此前只有当天的数** ——
   * 一次干净说明不了任何事：切真相源之前要看的是它连着干净了多久。
   *
   * 数据不用新建：`inv-recon` 每天跑一轮，结论就落在它自己的运行记录里
   * （成功 = 那天对差为零）。**另存一份的话，两处迟早对同一天给出两种说法。**
   */
  const runs = useQuery({
    queryKey: ["job-logs", "inv-recon"],
    queryFn: () => api.listJobLogs({ name: "inv-recon", size: 30 }),
  });
  const streak = cleanStreak(runs.data);
  /*
   * 要求连续几轮。**没有这个 N，「已连续 3 轮」答不出「够了没有」** ——
   * 而「够了没有」正是这一页要回答的唯一问题。
   */
  const policy = useQuery({ queryKey: ["inv-policy"], queryFn: () => api.invPolicy() });
  const required = policy.data?.reconCleanStreakRequired;

  const repair = useMutation({
    mutationFn: api.repairProjection,
    onSuccess: () => recon.refetch(),
  });

  /** 存疑打标。**只记录，不封店、不降权** —— 见后端那段说明 */
  const doubt = useMutation({
    mutationFn: api.markStockDoubt,
    onSuccess: () => notify.success(c.invDoubtDone),
  });

  const runRepair = async () => {
    const dry = await repair.mutateAsync({});
    if (dry.pending === 0) {
      notify.info(c.invRepairNothing);
      return;
    }
    await confirm({
      title: fill(c.invRepairTitle, { n: dry.pending }),
      desc: c.invRepairDesc,
      confirmText: c.invRepairConfirm,
      action: async () => {
        const done = await repair.mutateAsync({ apply: true });
        notify.success(fill(c.invRepairDone, { n: done.moved }));
      },
    });
  };

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
      header: c.invColHeld,
      numeric: true,
      // 预留对不上要单独看得见：实存一样时它是唯一的差异，而它同样会导致超卖
      cell: (d) => (
        <span className={d.platformHeld !== d.inventoryHeld ? "font-semibold text-destructive" : undefined}>
          {d.platformHeld} / {d.inventoryHeld}
        </span>
      ),
    },
    {
      header: c.invColGap,
      numeric: true,
      cell: (d) => {
        const gap = d.inventoryQty - d.platformQty;
        return <span className="font-semibold text-destructive">{gap > 0 ? `+${gap}` : gap}</span>;
      },
    },
    {
      header: c.invColAction,
      /*
       * 存疑打标。**这一列是每行一个商家，不是每行一件货** ——
       * 「这本账可不可信」是关于一家商家的判断，逐件货打标既做不完、
       * 也说不出「所以这家怎么了」。
       */
      cell: (d) => (
        <Button size="sm" variant="ghost" disabled={doubt.isPending}
          onClick={() => void confirm({
            title: fill(c.invDoubtTitle, { name: d.entityNo }),
            desc: c.invDoubtDesc,
            confirmText: c.invDoubtConfirm,
            // 必填理由：这条记录会进信用档案，商家问「凭什么」要答得上
            requireReason: true,
            action: (reason) => doubt.mutateAsync({ entityNo: d.entityNo, detail: reason }),
          })}>{c.invDoubt}</Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <HelpNote>{c.invReconNotice}</HelpNote>

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
            {c.invReconPending} {r.pending} · {c.invReconDiffs} {r.diffs.length}
          </span>
        </Notice>
      )}

      {dialog}

      {/*
        * 连续干净的天数。**这才是 G3 的判据** —— 顶上那条横幅说的是「此刻」，
        * 而能不能切真相源要看「连着多久」。跑失败与跑没跑过要分开：
        * 「连续 0 天」和「还没跑过」都不能切，但前者是有差异、后者是没有依据。
        */}
      {runs.data && runs.data.length === 0 && (
        <Notice tone="warning">{c.invStreakNeverRan}</Notice>
      )}
      {runs.data && runs.data.length > 0 && (
        <Notice tone={required != null && streak >= required ? "info" : "warning"}>
          {streak > 0 ? fill(c.invStreakClean, { n: streak }) : c.invStreakBroken}
          {/* 差多少轮才够，比「已经几轮」更是这一页要答的那个问题 */}
          {required != null && (
            <span className="ms-2 font-semibold">
              {streak >= required
                ? fill(c.invStreakEnough, { n: required })
                : fill(c.invStreakShort, { n: required - streak, need: required })}
            </span>
          )}
          <span className="ms-2 txt-caption text-muted-foreground">
            {fill(c.invStreakOf, { n: runs.data.length })}
          </span>
        </Notice>
      )}

      {/* 待搬与有差异是**两种**不合格，理由与处置都不同，分开说 */}
      {r && r.pending > 0 && (
        <Notice tone="warning">
          <div className="flex flex-wrap items-center gap-2">
            <span>{c.invReconPendingHint}</span>
            {/* 出路就放在说明旁边：只说问题不给下一步，看的人还是只能干等 */}
            {canRepair && (
              <Button size="sm" variant="outline"
                      onClick={() => void runRepair()} disabled={repair.isPending}>
                {c.invRepair}
              </Button>
            )}
          </div>
        </Notice>
      )}
      {r && r.diffs.length > 0 && <Notice tone="warning">{c.invReconHowTo}</Notice>}

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
