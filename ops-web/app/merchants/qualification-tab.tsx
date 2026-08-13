"use client";

// 商家已登记资质（后端 `mch_qualification`）。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么这一页值得单独存在
// ─────────────────────────────────────────────────────────────────────────────
// 上架时的两个闸门 —— 「资质过期」（QualificationExpiryJob + hasExpiredQualification）
// 与「类目授权」（sys_auth_code.required_qualification）—— 读的都是这张表。
//
// 而它此前**实测 0 行**：入驻收的执照停在申请单里，审核通过时没人转存，
// 后端三个管理接口虽然早就实现了，**前端一个调用方都没有**。
// 于是两个闸门都写好了、都从不触发，且不报任何错。
//
// V79 接上了「审核通过 → 转存」这条自动链路，这一页补的是**人工那条** ——
// 补录历史资质、证件换发后更新、作废时撤销。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import type { Qualification } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { MerchantsCopy } from "./copy";

const STATUS_TONE: Record<string, "success" | "warning" | "muted"> = {
  VALID: "success",
  EXPIRED: "warning",
  REVOKED: "muted",
};

export function QualificationTab({ c }: { c: MerchantsCopy }) {
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const [merchantNo, setMerchantNo] = useState("");
  const [queried, setQueried] = useState("");

  const canEdit = allow("merchant:category:grant");

  const list = useQuery({
    queryKey: ["qualifications", queried],
    queryFn: () => api.qualifications(queried),
    enabled: !!queried,
  });

  const revoke = useMutation({
    mutationFn: (qualNo: string) => api.revokeQualification(qualNo),
    onSuccess: () => {
      notify.success(c.qualRevoked);
      qc.invalidateQueries({ queryKey: ["qualifications", queried] });
    },
  });

  const cols: Column<Qualification>[] = [
    {
      header: c.qualColType,
      cell: (q) => <Badge tone="info">{qualTypeLabel(c)[q.qualType] ?? q.qualType}</Badge>,
    },
    { header: c.qualColName, cell: (q) => q.qualName },
    { header: c.qualColNumber, cell: (q) => q.qualNumber || "—" },
    {
      header: c.qualColExpire,
      // null 是「长期有效」，**不是「没填」** —— 显示成空白的话，
      // 运营会以为这条资料缺失而去补一个日期，而那会让它开始过期
      cell: (q) =>
        q.expireAt
          ? <span className="tabular-nums">{fmtTime(q.expireAt)}</span>
          : <span className="text-muted-foreground">{c.applyQualForever}</span>,
    },
    {
      header: c.qualColStatus,
      cell: (q) => (
        <Badge tone={STATUS_TONE[q.status] ?? "muted"}>
          {statusLabel(c)[q.status] ?? q.status}
        </Badge>
      ),
    },
    {
      header: c.qualColActions,
      cell: (q) => (
        <Button
          size="sm"
          variant="outline"
          disabled={!canEdit || q.status === "REVOKED"}
          onClick={async () => {
            if (await confirm({ title: c.qualRevoke, desc: c.qualRevokeConfirm, danger: true })) {
              revoke.mutate(q.qualNo);
            }
          }}
        >
          {c.qualRevoke}
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="warning">{c.qualHint}</Notice>

      <Toolbar>
        <Input
          value={merchantNo}
          onChange={(e) => setMerchantNo(e.target.value)}
          placeholder="M901"
          className="w-56"
        />
        <Button onClick={() => setQueried(merchantNo.trim())} disabled={!merchantNo.trim()}>
          {c.adSearch}
        </Button>
      </Toolbar>

      {queried && (
        <ConfigCard title={c.qualTitle}>
          <DataTable
            columns={cols}
            rows={list.data ?? []}
            rowKey={(q) => q.qualNo}
            loading={list.isLoading}
            empty={c.qualEmpty}
          />
        </ConfigCard>
      )}
      {dialog}
    </div>
  );
}

/** 文案表是扁平的，枚举 → 文案的映射在组件里拼 */
const qualTypeLabel = (c: MerchantsCopy): Record<string, string> => ({
  BUSINESS_LICENSE: c.qualBUSINESS_LICENSE,
  FOOD_PERMIT: c.qualFOOD_PERMIT,
  FOOD_WORKSHOP: c.qualFOOD_WORKSHOP,
  OTHER: c.qualOTHER,
});

const statusLabel = (c: MerchantsCopy): Record<string, string> => ({
  VALID: c.qualStatusVALID,
  EXPIRED: c.qualStatusEXPIRED,
  REVOKED: c.qualStatusREVOKED,
});
