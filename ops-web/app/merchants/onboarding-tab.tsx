"use client";

// 进件看板（WS-C）—— **只读 + 一个人工回查**。
//
// 它补的是「审核过了但收不了钱」的盲区：入驻审核与收款进件是两条链 ——
// 审核通过 = 能上架卖货，进件通过 = 能收钱。审核过的商家货照上、单照来，
// 进件没走完就是钱收不到，而运营端此前没有一个跨商家的地方能看见这件事。
//
// **不碰通道**：看板只读后端的进件记录，回查转调后端已有的 refresh
// （真实进件通道属支付方案，接通与否本页都能用 —— stub 下照样显示状态）。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { fill } from "@/lib/use-copy";
import type { OnboardingRow, OnboardingStatus } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import type { MerchantsCopy } from "./copy";

const TONES: Record<OnboardingStatus, "muted" | "warning" | "success" | "danger"> = {
  NONE: "muted",
  APPLYING: "warning",
  ACTIVE: "success",
  REJECTED: "danger",
  FROZEN: "muted",
};

/** 停留时长：越久越该有人去问。null = 还没提交（占位记录） */
function stayLabel(c: MerchantsCopy, ageMs: number | null): string {
  if (ageMs == null) return c.obStayNotSubmitted;
  const days = Math.floor(ageMs / 86_400_000);
  if (days >= 1) return fill(c.obStayDays, { n: days });
  return fill(c.obStayHours, { n: Math.floor(ageMs / 3_600_000) });
}

export function OnboardingTab({ c }: { c: MerchantsCopy }) {
  const qc = useQueryClient();
  const allow = useCan();
  const canRefresh = allow("merchant:admission:update");

  const statusMap: StatusMap<OnboardingStatus> = {
    NONE: { label: c.obStNONE, tone: TONES.NONE },
    APPLYING: { label: c.obStAPPLYING, tone: TONES.APPLYING },
    ACTIVE: { label: c.obStACTIVE, tone: TONES.ACTIVE },
    REJECTED: { label: c.obStREJECTED, tone: TONES.REJECTED },
    FROZEN: { label: c.obStFROZEN, tone: TONES.FROZEN },
  };
  const channelLabel = (ch: string): string =>
    ch === "WECHAT" ? c.obChannelWECHAT : ch === "ALIPAY" ? c.obChannelALIPAY : ch;

  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [payChannel, setPayChannel] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const q = { keyword, status, payChannel, page, size };
  const list = useQuery({ queryKey: ["onboarding", q], queryFn: () => api.onboardingBoard(q) });

  const refresh = useMutation({
    mutationFn: (r: OnboardingRow) =>
      api.refreshOnboarding({ merchantNo: r.merchantNo, payChannel: r.payChannel, storeNo: r.storeNo }),
    // 回查落库后重取看板 —— 通道那边若已批，这一下状态就翻成「已开通」
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["onboarding"] });
      /*
       * ★ 从没提交过的占位行，后端根本没去问通道，状态一定不动。
       * 这时报「已回查」是骗人的：运营会一直等通道，而球在商家脚下。
       */
      if (r.submitted) { notify.success(c.obToastRefreshed); } else { notify.info(c.obToastNotSubmitted); }
    },
  });

  const columns: Column<OnboardingRow>[] = [
    {
      header: c.obColMerchant,
      cell: (r) => (
        <div>
          <div>{r.merchantName}</div>
          <div className="txt-caption tabular-nums text-muted-foreground">{r.merchantNo}</div>
        </div>
      ),
    },
    { header: c.obColChannel, cell: (r) => channelLabel(r.payChannel) },
    { header: c.obColStatus, cell: (r) => <StatusBadge value={r.applyStatus} map={statusMap} /> },
    {
      // 一眼看出「卡在哪」：被拒给原因，没被拒但收不了钱给「收款号未生成」
      header: c.obColBlocker,
      cell: (r) =>
        r.rejectReason ? (
          <span className="text-[var(--danger)]">{r.rejectReason}</span>
        ) : r.canReceiveMoney ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span className="text-[var(--warning)]">{c.obBlockerNoPayNo}</span>
        ),
    },
    {
      header: c.obColPayNo,
      cell: (r) =>
        r.payMerchantNo ? (
          <span className="tabular-nums">{r.payMerchantNo}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    { header: c.obColStay, cell: (r) => stayLabel(c, r.ageMs) },
    { header: c.obColAppliedAt, cell: (r) => (r.appliedAt ? fmtTime(r.appliedAt) : "—") },
    {
      header: c.obColActions,
      cell: (r) =>
        // 已开通的没什么可回查；未开通的才给按钮，且要 admission:update 权限
        canRefresh && r.applyStatus !== "ACTIVE" ? (
          <Button
            size="sm"
            variant="outline"
            disabled={refresh.isPending}
            onClick={() => refresh.mutate(r)}
          >
            {c.obActionRefresh}
          </Button>
        ) : null,
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.obNotice}</HelpNote>

      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={c.obSearchPh}
      >
        <FilterSelect
          value={status}
          onChange={(v) => { setStatus(v); setPage(1); }}
          options={[
            { value: "", label: c.obFilterAllStatus },
            { value: "APPLYING", label: c.obStAPPLYING },
            { value: "REJECTED", label: c.obStREJECTED },
            { value: "ACTIVE", label: c.obStACTIVE },
            { value: "APPLYING,REJECTED", label: c.obFilterTodo },
          ]}
        />
        <FilterSelect
          value={payChannel}
          onChange={(v) => { setPayChannel(v); setPage(1); }}
          options={[
            { value: "", label: c.obFilterAllChannel },
            { value: "WECHAT", label: c.obChannelWECHAT },
            { value: "ALIPAY", label: c.obChannelALIPAY },
          ]}
        />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records ?? []}
        loading={list.isPending}
        rowKey={(r) => `${r.merchantNo}:${r.payChannel}:${r.storeNo}`}
        empty={c.obEmpty}
      />
      <Pagination
        page={page}
        size={size}
        total={list.data?.total ?? 0}
        onPage={setPage}
        onSize={(v) => { setSize(v); setPage(1); }}
      />
    </>
  );
}
