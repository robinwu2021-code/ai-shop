"use client";

// 券与活动的**敞口**（P8 · O5–O6）。
//
// 这两张表看的不是「有哪些券」，而是**「哪些会失控」**：谁家的券没设预算、
// 不限量、单张优惠过大、限量快用完。商家自己看不出来 —— 他只看得到他那一张；
// 跨商家排在一起才看得见。
//
// 挂在营销页而不是会员页：它们是营销的事，权限码也是 marketing:*。
// （nav 里那两条深链指向 /members?tab=…，但页面在这儿 —— 见 nav.ts 的注释。）
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { money } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { OpsPromoActivity, OpsPromoCoupon } from "@/lib/types";
import type { MarketingCopy } from "./copy";

export function ExposureTab({ c, kind, canStop }: {
  c: MarketingCopy;
  kind: "coupons" | "activities";
  canStop: boolean;
}) {
  const qc = useQueryClient();

  const coupons = useQuery({
    queryKey: ["ops-promo-coupons"],
    queryFn: () => api.listOpsPromoCoupons(),
    enabled: kind === "coupons",
  });
  const activities = useQuery({
    queryKey: ["ops-promo-activities"],
    queryFn: () => api.listOpsPromoActivities(),
    enabled: kind === "activities",
  });

  const stop = useMutation({
    mutationFn: ({ no, reason }: { no: string; reason: string }) =>
      api.stopOpsActivity(no, reason),
    onSuccess: () => {
      notify.success(c.exposureStopDone);
      void qc.invalidateQueries({ queryKey: ["ops-promo-activities"] });
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const flags = (list: string[]) => (
    <div className="flex flex-wrap gap-1">
      {list.map((f) => (
        <Badge key={f} tone="danger">
          {(c as unknown as Record<string, string>)["flag_" + f] ?? f}
        </Badge>
      ))}
    </div>
  );

  const couponCols: Column<OpsPromoCoupon>[] = [
    { header: c.exposureTitle, cell: (x) => x.title },
    { header: c.exposureEntity, cell: (x) => x.entityName },
    { header: c.exposureIssued, cell: (x) => `${x.receivedCount} / ${x.totalCount ?? c.exposureUnlimited}` },
    { header: c.exposureBudget, cell: (x) => (x.budgetMinor ? money(x.budgetMinor) : c.exposureNone) },
    { header: c.exposureMax, cell: (x) => (x.maxExposureMinor == null ? c.exposureUnlimited : money(x.maxExposureMinor)) },
    { header: c.exposureFlags, cell: (x) => flags(x.flags) },
  ];

  const activityCols: Column<OpsPromoActivity>[] = [
    { header: c.exposureActivity, cell: (x) => x.name },
    { header: c.exposureEntity, cell: (x) => x.entityName },
    { header: c.exposureSchedule, cell: (x) => x.scheduleType },
    { header: c.exposureQuota, cell: (x) => (x.quota == null ? c.exposureUnlimited : `${x.quotaUsed} / ${x.quota}`) },
    // 0 条受众 = 对所有人生效。**这一列必须显示** ——
    // 「给所有人」与「没设置」在库里长得一样，含义差很远
    { header: c.exposureAudience, cell: (x) => (x.audienceCount === 0 ? c.exposureAudienceAll : String(x.audienceCount)) },
    { header: c.exposureFlags, cell: (x) => flags(x.flags) },
    { header: "", cell: (x) => (canStop && x.status === "RUNNING" ? (
      <Button
        size="sm"
        variant="destructive"
        onClick={() => {
          // 原因必填且商家可见 —— 不给理由的话，商家看到的是「我的活动莫名其妙没了」
          const reason = window.prompt(c.exposureStopReason) ?? "";
          if (reason.trim().length >= 4) stop.mutate({ no: x.activityNo, reason });
        }}
      >{c.exposureStop}</Button>
    ) : null) },
  ];

  return (
    <div className="space-y-3">
      <Notice>{kind === "coupons" ? c.exposureCouponHint : c.exposureActivityHint}</Notice>
      {kind === "coupons"
        ? <DataTable columns={couponCols} rows={coupons.data ?? []} rowKey={(x) => x.couponNo} />
        : <DataTable columns={activityCols} rows={activities.data ?? []} rowKey={(x) => x.activityNo} />}
    </div>
  );
}
