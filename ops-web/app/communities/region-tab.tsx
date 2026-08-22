"use client";

// 商家补录的村级区划。
//
// 这一屏补的是「商家补录」那条链的**后半截**：商家录完只对他自己可见，
// 而在此之前运营没有任何地方能把它转成全平台共享 —— 只能手工改库。
//
// 为什么要有商家补录这条路：官方村级数据停在 2023-06-30
// （国家统计局 2024-10 起不再公开具体代码），之后新增或改名的村
// 没有任何官方渠道能拿到。缺一个村，那一片就做不了生意，
// 而「等平台下次更新」在源头停发之后根本不会到来。
//
// 裁决面上最要紧的一件事：**整条路径必须显眼**。
// 光一个「新桥社区」，全国有好几个 —— 运营看不到
// 「浙江省 / 杭州市 / 西湖区 / 西溪街道」就判断不了真假，
// 只能靠猜，而猜的结果是直接通过。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { PendingRegion } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

const useRegionStatusMap = (c: Copy): StatusMap<PendingRegion["auditStatus"]> => ({
  PENDING: { label: c.rgPending, tone: "warning" },
  APPROVED: { label: c.rgApproved, tone: "success" },
  REJECTED: { label: c.rgRejected, tone: "muted" },
});

export function RegionTab({ c, canDecide }: { c: Copy; canDecide: boolean }) {
  const qc = useQueryClient();
  const statusMap = useRegionStatusMap(c);
  // 默认只看待确认：这是个队列，历史是次要视图
  const [status, setStatus] = useState("PENDING");
  const [current, setCurrent] = useState<PendingRegion | null>(null);
  const [reason, setReason] = useState("");

  const list = useQuery({
    queryKey: ["pendingRegions", status],
    queryFn: () => api.listPendingRegions(status),
  });

  const decide = useMutation({
    mutationFn: (pass: boolean) => api.confirmRegion(current!.regionCode, pass, reason),
    onSuccess: (_, pass) => {
      qc.invalidateQueries({ queryKey: ["pendingRegions"] });
      setCurrent(null);
      notify.success(pass ? c.toastRegionApproved : c.toastRegionRejected);
    },
  });

  const columns: Column<PendingRegion>[] = [
    { header: c.colRgName, cell: (r) => <button className="link" onClick={() => { setCurrent(r); setReason(""); }}>{r.name}</button> },
    // 整条路径就是判据本身，所以进列表而不是只在详情里
    { header: c.colRgPath, cell: (r) => <span className="line-clamp-1 text-muted-foreground">{r.path}</span> },
    { header: c.colRgMerchant, cell: (r) => r.entityName },
    { header: c.colRgStatus, cell: (r) => <StatusBadge value={r.auditStatus} map={statusMap} /> },
    { header: c.colRgTime, cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <Toolbar>
        <FilterSelect
          aria-label={c.filterRgStatus}
          value={status}
          onChange={(v) => setStatus(v || "PENDING")}
          options={statusMap}
          allLabel={c.filterRgStatusAll}
        />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => r.regionCode}
        empty={c.emptyRegion}
      />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.name ?? ""}
        desc={current ? statusMap[current.auditStatus].label : undefined}
        width="w-[520px]"
        footer={
          current && canDecide && current.auditStatus === "PENDING" ? (
            <>
              <Button loading={decide.isPending} onClick={() => decide.mutate(true)}>
                {c.btnRgPass}
              </Button>
              <Button
                variant="outline"
                loading={decide.isPending}
                // 驳回必须写原因：它原样回给商家，不写的话他只会原样再提一次
                disabled={!reason.trim()}
                onClick={() => decide.mutate(false)}
              >
                {c.btnRgReject}
              </Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secRgWhat}>
              <FieldGrid>
                <Field className="mb-3" label={c.colRgMerchant}>{current.entityName}</Field>
                <Field className="mb-3" label={c.colRgTime}>{fmtTime(current.createdAt)}</Field>
              </FieldGrid>
              <Field className="mb-3" label={c.colRgPath}>{current.path}</Field>
              <Field className="mb-0" label={c.fieldRgCode}>{current.regionCode}</Field>
              <p className="mt-2 txt-caption text-muted-foreground">{c.rgDupHint}</p>
            </DrawerSection>

            {current.auditStatus === "REJECTED" && current.rejectReason && (
              <DrawerSection title={c.secRgRejected}>
                <p className="txt-body">{current.rejectReason}</p>
              </DrawerSection>
            )}

            {current.auditStatus === "PENDING" && canDecide && (
              <DrawerSection title={c.secRgDecide}>
                <Textarea value={reason} onChange={setReason} rows={3} placeholder={c.phRgReason} />
                <p className="mt-2 txt-caption text-muted-foreground">{c.rgReasonHint}</p>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
