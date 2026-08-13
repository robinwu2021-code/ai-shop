"use client";

// 无营业执照的主体 × 自营门店 —— 税务敞口清单。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这不是异常报表，是现状盘点
// ─────────────────────────────────────────────────────────────────────────────
// `mch_store.business_mode` 的默认值就是自营，且后端**没有任何一处**校验
// 「无照不得自营」。所以「无照 + 自营」不是谁配错了，是**必然结果** ——
// 一个没有执照的商家建店，就自动落在这个组合上。
//
// 后果是税务的：自营意味着平台是法律上的销售主体，列支成本要取得进项发票，
// 而无照主体开不出票。这笔支出**不得在企业所得税前扣除** ——
// 不是「多交一点税」，是账面上凭空多出等额利润。
//
// 为什么只看不拦：硬拦会同时打断两件事 —— 存量商户当场停业，
// 以及农产品供应商（农户正是「无照 + 自营采购」，那是法规为农产品单开的
// 合规路径，平台自开收购发票）。区分它们要靠农业生产者标记，而它还不存在。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { ModeRisk } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { ConfigCard } from "@/components/ui/config-card";
import type { MERCHANTS_COPY } from "./copy";

type Copy = (typeof MERCHANTS_COPY)["zh"];

export function ModeRiskTab({ c }: { c: Copy }) {
  const list = useQuery({ queryKey: ["mode-risk"], queryFn: () => api.modeRisk() });
  const rows = list.data ?? [];
  const totalMinor = rows.reduce((n, r) => n + r.settledMinor, 0);
  const traded = rows.filter((r) => r.settledBills > 0).length;

  const cols: Column<ModeRisk>[] = [
    { header: c.mrColMerchant, cell: (r) => r.merchantName },
    {
      header: c.mrColLegalForm,
      // 显示「无营业执照」而不是档位码：这一列要回答的是「为什么它在这张表上」，
      // 而 MICRO 这个码既不解释原因，还与法规「小微企业（有照）」重名
      cell: () => <Badge tone="warning">{c.mrNoLicense}</Badge>,
    },
    { header: c.mrColStore, cell: (r) => r.storeName },
    {
      header: c.mrColBills,
      cell: (r) =>
        r.settledBills > 0
          ? r.settledBills
          // 0 是「查过了，还没成交」——**这一行仍要显示**，它是即将发生的敞口，
          // 也正是最该在成交之前处理掉的那些
          : <span className="text-muted-foreground">{c.mrNotTraded}</span>,
    },
    {
      header: c.mrColExposure,
      cell: (r) =>
        r.settledMinor > 0
          ? <span className="font-medium tabular-nums">{money(r.settledMinor)}</span>
          : <span className="text-muted-foreground">—</span>,
    },
  ];

  return (
    <div className="space-y-4">
      {/* 不可关闭、不是 toast：这段说明是这张表的判读方式，
          每次打开都要在，而不是看过一次就消失 */}
      <Notice tone="danger">{c.mrWarning}</Notice>

      {rows.length > 0 && (
        <Notice tone="muted">
          {c.mrSummary
            .replace("{stores}", String(rows.length))
            .replace("{traded}", String(traded))
            .replace("{amount}", money(totalMinor))}
        </Notice>
      )}

      <ConfigCard title={c.mrTitle} notice={c.mrDesc}>
        <DataTable
          columns={cols}
          rows={rows}
          rowKey={(r) => r.storeNo}
          loading={list.isLoading}
          empty={c.mrEmpty}
        />
      </ConfigCard>
    </div>
  );
}
