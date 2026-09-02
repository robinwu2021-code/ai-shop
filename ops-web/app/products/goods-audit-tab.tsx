"use client";

// 商品审核（P-3.2）—— **已接真后端** `/ops/goods/**`。
//
// 粒度是 **goods 不是 sku**：审的是「这件商品能不能卖」，
// 标题、图、类目、资质都挂在 goods 上；sku 只是规格与价格。
// 拿 sku 粒度去审，同一件商品会被审好几遍，而审核人力是这条链上最贵的资源。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { usePaging } from "@/lib/use-paging";
import type { GoodsAudit } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Pagination } from "@/components/ui/misc";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { ProductsCopy } from "./copy";

export function GoodsAuditTab({ c, canAudit }: { c: ProductsCopy; canAudit: boolean }) {
  const qc = useQueryClient();
  const { page, setPage, size, setSize } = usePaging();
  const [current, setCurrent] = useState<GoodsAudit | null>(null);
  const [reason, setReason] = useState("");

  const q = { page, size };
  const list = useQuery({
    queryKey: ["goods-audit", q],
    queryFn: () => api.listGoodsAuditQueue(q),
  });

  const audit = useMutation({
    mutationFn: (v: { approved: boolean }) =>
      api.auditGoods(current!.goodsNo, v.approved, reason),
    onSuccess: (_, v) => {
      qc.invalidateQueries({ queryKey: ["goods-audit"] });
      setCurrent(null);
      notify.success(v.approved ? c.gaApproved : c.gaRejected);
    },
  });

  /*
   * 待审草稿的字段级差异（双版本）。**没有这一段，审核员批准的是自己没看过的版本**：
   * 审核开着时线上照卖旧版，详情抽屉里那份也是旧版 —— 真正要生效的是这份 diff。
   * null = 老链路（新建提审等），审的是内容本身，这一段整个不渲染。
   */
  const draftPreview = useQuery({
    queryKey: ["goods-draft-preview", current?.goodsNo],
    queryFn: () => api.goodsDraftPreview(current!.goodsNo),
    enabled: !!current,
  });

  const columns: Column<GoodsAudit>[] = [
    { header: c.gaColNo, cell: (g) => g.goodsNo, numeric: true, align: "start" },
    { header: c.gaColTitle, cell: (g) => g.title },
    { header: c.gaColType, cell: (g) => g.type },
    {
      // 类目当前恒为空（商品编辑页还没有选类目这一步）—— 标出来，
      // 免得审核的人以为是这件商品漏填了
      header: c.gaColCategory,
      cell: (g) => g.categoryNo ?? <span className="text-[var(--muted)]">{c.gaCategoryEmpty}</span>,
    },
    { header: c.gaColMerchant, cell: (g) => g.merchant?.name ?? "—" },
    {
      header: c.gaColActions,
      cell: (g) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(g); setReason(""); }}>
          {c.gaAction}
        </Button>
      ),
    },
  ];

  /*
   * 社区池重建。放在审核这一屏而不是单开一个 tab：后端挂的就是
   * `product:sku:audit`（「能决定一件商品能不能卖的人，才该能重算它出现在哪儿」），
   * 而它的触发场景也正是审核/客服在处理的那类问题。
   */
  const [resyncScope, setResyncScope] = useState("");
  const resync = useMutation({
    mutationFn: () => api.resyncCommunityPool(resyncScope.trim() || undefined),
    onSuccess: (n) => notify.success(
      n > 0 ? c.rsDone.replace("{n}", String(n)) : c.rsDoneZero),
  });

  return (
    <>
      {!canAudit && (
        <Notice tone="warning" className="mb-3">{c.gaReadOnly}</Notice>
      )}

      {canAudit && (
        <div className="mb-4 rounded-card border p-3">
          <div className="mb-2 font-medium">{c.rsTitle}</div>
          <HelpNote className="mb-3">{c.rsNotice}</HelpNote>
          <div className="flex flex-wrap items-end gap-2">
            <div className="space-y-1">
              <Label htmlFor="rs-scope">{c.rsScope}</Label>
              <Input id="rs-scope" className="w-64" placeholder={c.rsPlaceholder}
                value={resyncScope} onChange={(e) => setResyncScope(e.target.value)} />
            </div>
            <Button disabled={resync.isPending} onClick={() => resync.mutate()}>
              {resync.isPending ? c.rsRunning : c.rsRun}
            </Button>
          </div>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={list.data?.records ?? []}
        loading={list.isPending}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(g) => g.goodsNo}
        empty={c.gaEmpty}
      />
      <Pagination
        page={page}
        size={size}
        total={list.data?.total ?? 0}
        onPage={setPage}
        onSize={(v) => { setSize(v); setPage(1); }}
      />

      <Drawer open={!!current} onOpenChange={(o) => !o && setCurrent(null)} title={current?.title ?? ""}>
        {current && (
          <>
            <DrawerSection title={c.gaSectionInfo}>
              <FieldGrid>
                <Field label={c.gaColNo}>{current.goodsNo}</Field>
                <Field label={c.gaColType}>{current.type}</Field>
                <Field label={c.gaColCategory}>{current.categoryNo ?? c.gaCategoryEmpty}</Field>
                <Field label={c.gaColMerchant}>{current.merchant?.name ?? "—"}</Field>
              </FieldGrid>
              {current.subtitle && (
                <p className="mt-2 text-sm text-[var(--muted)]">{current.subtitle}</p>
              )}
              {current.status === "REJECTED" && (
                <Notice tone="danger" className="mt-3">{c.gaRejectedTag}</Notice>
              )}
            </DrawerSection>

            {draftPreview.data && (
              <DrawerSection title={c.gaDraftSection}>
                <HelpNote className="mb-3">{c.gaDraftHint}</HelpNote>
                {draftPreview.data.blocked.length > 0 && (
                  <Notice tone="warning" className="mb-3">
                    {c.gaDraftBlocked}
                    {draftPreview.data.blocked.join("、")}
                  </Notice>
                )}
                <div className="space-y-2">
                  {draftPreview.data.changes.map((r) => (
                    <div key={r.field} className="text-sm">
                      <span className="text-[var(--muted)]">{r.label}：</span>
                      <span className="line-through text-[var(--muted)]">{r.before ?? "—"}</span>
                      <span className="mx-1 text-[var(--muted)]">→</span>
                      <span>{r.after ?? "—"}</span>
                    </div>
                  ))}
                  {draftPreview.data.changes.length === 0 && (
                    <p className="text-sm text-[var(--muted)]">{c.gaDraftNoChange}</p>
                  )}
                </div>
              </DrawerSection>
            )}

            {canAudit && (
              <DrawerSection title={c.gaSectionDecide}>
                <Label>{c.gaReasonLabel}</Label>
                <Textarea
                  value={reason}
                  onChange={(v) => setReason(v)}
                  placeholder={c.gaReasonPh}
                />
                <div className="mt-4 flex gap-2">
                  <Button disabled={audit.isPending} onClick={() => audit.mutate({ approved: true })}>
                    {c.gaApprove}
                  </Button>
                  <Button
                    variant="outline"
                    disabled={!reason.trim() || audit.isPending}
                    onClick={() => audit.mutate({ approved: false })}
                  >
                    {c.gaReject}
                  </Button>
                </div>
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
