"use client";

// 商家提报的新社区（ADR-013 阶段三）。
//
// 这一屏补的是一条**死路**：商家开在平台还没开的小区里，覆盖项只能从已有社区里勾，
// 而「让平台加一个小区」此前没有任何入口 —— 只能找 BD 口头说，说完没人知道进展。
//
// 裁决面上最要紧的两件事：
//   1. **地址要显眼** —— 运营真正要判断的是「这是不是已有社区的另一个叫法」，
//      而重名小区在同一个城市里就有好几个。判错的后果是建出两个同名社区，
//      商家勾选时分不清该勾哪个；
//   2. **通过时要挂区划** —— 不挂的话这个新社区在任何「按区覆盖」里都出不来，
//      而界面上它看起来完全正常。所以这里默认带上商家填的，且允许运营改。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import type { CommunityApply } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { RegionChooser } from "./region-chooser";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

const useApplyStatusMap = (c: Copy): StatusMap<CommunityApply["status"]> => ({
  PENDING: { label: c.apPending, tone: "warning" },
  APPROVED: { label: c.apApproved, tone: "success" },
  REJECTED: { label: c.apRejected, tone: "muted" },
});

export function ApplyTab({ c, canDecide }: { c: Copy; canDecide: boolean }) {
  const qc = useQueryClient();
  const statusMap = useApplyStatusMap(c);
  // 默认只看待审：这是个队列，历史是次要视图
  const [status, setStatus] = useState("PENDING");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<CommunityApply | null>(null);
  const [regionCode, setRegionCode] = useState("");
  const [reason, setReason] = useState("");

  const q = { status, page, size };
  const list = useQuery({
    queryKey: ["communityApplies", q],
    queryFn: () => api.listCommunityApplies(q),
  });

  const open = (a: CommunityApply) => {
    setCurrent(a);
    // 默认带上商家填的区划：多数情况下它是对的，而空着会让人忘记挂
    setRegionCode(a.regionCode ?? "");
    setReason("");
  };

  const decide = useMutation({
    mutationFn: (pass: boolean) =>
      api.decideCommunityApply(current!.applyNo, pass, { regionCode, reason }),
    onSuccess: (a) => {
      qc.invalidateQueries({ queryKey: ["communityApplies"] });
      // 通过会**当场建出一个社区**，社区列表必须跟着刷新
      qc.invalidateQueries({ queryKey: ["communities"] });
      setCurrent(null);
      notify.success(a.status === "APPROVED" ? c.toastApplyApproved : c.toastApplyRejected);
    },
  });

  const columns: Column<CommunityApply>[] = [
    { header: c.colApplyName, cell: (a) => <button className="link" onClick={() => open(a)}>{a.name}</button> },
    { header: c.colApplyMerchant, cell: (a) => a.merchantName },
    // 地址就是判据本身，所以进列表而不是只在详情里
    { header: c.colApplyAddress, cell: (a) => <span className="line-clamp-1 text-muted-foreground">{a.address || c.applyNoValue}</span> },
    { header: c.colApplyRegion, cell: (a) => a.regionPath ?? c.applyNoValue },
    { header: c.colApplyStatus, cell: (a) => <StatusBadge value={a.status} map={statusMap} /> },
    { header: c.colApplyTime, cell: (a) => fmtTime(a.submittedAt) },
  ];

  return (
    <>
      <Toolbar>
        <FilterSelect
          aria-label={c.filterApplyStatus}
          value={status}
          onChange={(v) => { setStatus(v || "ALL"); setPage(1); }}
          options={statusMap}
          allLabel={c.filterApplyStatusAll}
        />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(a) => a.applyNo}
        empty={c.emptyApply}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.name ?? ""}
        desc={current ? statusMap[current.status].label : undefined}
        width="w-[520px]"
        footer={
          current && canDecide && current.status === "PENDING" ? (
            <>
              <Button loading={decide.isPending} onClick={() => decide.mutate(true)}>
                {c.btnApplyPass}
              </Button>
              <Button
                variant="outline"
                loading={decide.isPending}
                // 驳回必须写原因：它原样回给商家，不写的话他只会原样再提一次
                disabled={!reason.trim()}
                onClick={() => decide.mutate(false)}
              >
                {c.btnApplyReject}
              </Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secApplyWhat}>
              <FieldGrid>
                <Field className="mb-3" label={c.colApplyMerchant}>{current.merchantName}</Field>
                <Field className="mb-3" label={c.colApplyTime}>{fmtTime(current.submittedAt)}</Field>
              </FieldGrid>
              <Field className="mb-3" label={c.colApplyAddress}>{current.address || c.applyNoValue}</Field>
              <Field className="mb-0" label={c.fieldApplyNote}>{current.note || c.applyNoValue}</Field>
              <p className="mt-2 txt-caption text-muted-foreground">{c.applyDupHint}</p>
            </DrawerSection>

            {current.status === "PENDING" && (
              <>
                <DrawerSection title={c.secApplyRegion}>
                  {/* 逐级选而不是手敲国标码：330106003 与 330106004 只差一位，
                      而填错的后果是这个社区在任何「按区覆盖」里都出不来。联调时我自己就填错过一次 */}
                  <RegionChooser c={c} value={current.regionCode} onChange={(r) => setRegionCode(r?.regionCode ?? "")} />
                  {/* 不挂区划，这个新社区在任何「按区覆盖」里都出不来，而界面上它看起来完全正常 */}
                  <p className="mt-1 txt-caption text-muted-foreground">{c.applyRegionHint}</p>
                </DrawerSection>

                <DrawerSection title={c.secApplyReject}>
                  <Field className="mb-0" label={c.fieldApplyReason}>
                    <Textarea value={reason} onChange={setReason} rows={3} placeholder={c.applyReasonPlaceholder} />
                  </Field>
                  <p className="mt-1 txt-caption text-muted-foreground">{c.applyReasonHint}</p>
                </DrawerSection>
              </>
            )}

            {current.status === "APPROVED" && (
              <DrawerSection title={c.secApplyResult}>
                <Field className="mb-0" label={c.fieldApplyCommunity}>{current.communityNo}</Field>
              </DrawerSection>
            )}
            {current.status === "REJECTED" && (
              <DrawerSection title={c.secApplyResult}>
                <Field className="mb-0" label={c.fieldApplyReason}>{current.reason}</Field>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
