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
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
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

  /*
   * 补录 / 更新。**写接口 `POST /ops/merchants/{no}/qualifications` 一直都在，
   * 调用方一个都没有** —— 于是这一页顶上那句「补录历史资质、证件换发后更新」
   * 只兑现了「撤销」那三分之一。
   *
   * 两处不报错的坑：
   *   · expireAt 空 = **长期有效**，不是「没填」。空串转 0 会让它立刻算过期，
   *     上架被拦，而运营看到的只是「传了也没用」
   *   · qualName 是给类目授权做**前缀**比对的（LIKE「营业执照%」），
   *     写成「食品证」不会报错，只是那条授权从此永远不匹配
   */
  const [form, setForm] = useState<Record<string, unknown> | null>(null);

  const save = useMutation({
    mutationFn: () => api.saveQualification({
      merchantNo: queried,
      qualNo: (form!.qualNo as string) || undefined,
      qualType: String(form!.qualType ?? ""),
      qualName: String(form!.qualName ?? ""),
      qualNumber: String(form!.qualNumber ?? "") || undefined,
      imageUrl: String(form!.imageUrl ?? "") || undefined,
      // 空 = 长期有效，要发 null 而不是 0
      expireAt: form!.expireAt ? new Date(`${form!.expireAt}T00:00:00`).getTime() : null,
    }),
    onSuccess: () => {
      notify.success(c.qualSaved);
      setForm(null);
      qc.invalidateQueries({ queryKey: ["qualifications", queried] });
    },
  });

  const fields: FieldDef[] = [
    {
      key: "qualType", label: c.qualFieldType, type: "select", required: true,
      options: Object.entries(qualTypeLabel(c)).map(([value, label]) => ({ value, label })),
    },
    { key: "qualName", label: c.qualFieldName, required: true, help: c.qualFieldNameHelp },
    { key: "qualNumber", label: c.qualFieldNumber, help: c.qualFieldNumberHelp },
    { key: "imageUrl", label: c.qualFieldImage },
    { key: "expireAt", label: c.qualFieldExpire, type: "date", help: c.qualFieldExpireHelp },
  ];

  /** 分 → 表单。到期日从毫秒转 YYYY-MM-DD；null 保持空串 = 长期有效 */
  const openForm = (q?: Qualification) => setForm({
    qualNo: q?.qualNo ?? "",
    qualType: q?.qualType ?? "BUSINESS_LICENSE",
    qualName: q?.qualName ?? "",
    qualNumber: q?.qualNumber ?? "",
    imageUrl: q?.imageUrl ?? "",
    expireAt: q?.expireAt ? new Date(q.expireAt).toISOString().slice(0, 10) : "",
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
        <div className="flex gap-2">
        <Button size="sm" variant="outline" disabled={!canEdit} onClick={() => openForm(q)}>
          {c.qualEdit}
        </Button>
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
        </div>
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
        {/* 补录挂在「查过某个商家之后」—— 没有主体号就没有挂靠对象 */}
        {queried && canEdit && (
          <Button variant="outline" onClick={() => openForm()}>{c.qualAdd}</Button>
        )}
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
      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew={c.qualDrawerNew}
        titleEdit={c.qualDrawerEdit}
        isEdit={!!form?.qualNo}
        fields={fields}
        value={form ?? {}}
        onChange={setForm}
        onSubmit={() => save.mutate()}
        submitting={save.isPending}
      />
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
