"use client";

// 从快递100 生成运费模板时，各省的原始报价（TDD-快递100商家寄件 §8 AC17）。
//
// 单独一个文件，是因为它是**详情子表**：行数据取自已经到手的草稿，自己没有查询 ——
// 没有错误态可接（DataTable 的 error / onRetry 守卫按文件豁免，见 lib/design-tokens.test.ts）。
import { fill } from "@/lib/use-copy";
import { money } from "@/lib/utils";
import type { FreightProvincePrice, OutOfRangeRule } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import type { FulfillmentCopy } from "./copy";

export function FreightDraftRows({ c, rows, rules }: {
  c: FulfillmentCopy;
  rows: FreightProvincePrice[];
  /** 编辑中的地区规则 —— 运营改了加收额，这一列跟着变 */
  rules: OutOfRangeRule[];
}) {
  const columns: Column<FreightProvincePrice>[] = [
    { header: c.fieldRegion, cell: (r) => r.region },
    { header: c.colDraftFirst, cell: (r) => (r.firstFee == null ? c.none : money(r.firstFee)), numeric: true },
    { header: c.colDraftAdd, cell: (r) => (r.addFee == null ? c.none : money(r.addFee)), numeric: true },
    {
      header: c.colDraftResult,
      cell: (r) => {
        const rule = rules.find((x) => x.region === r.region);
        if (!rule) return <span className="text-muted-foreground">{c.resultBase}</span>;
        return rule.action === "REJECT"
          ? <Badge tone="danger">{c.outReject}</Badge>
          : fill(c.resultSurcharge, { fee: money(rule.surcharge) });
      },
      numeric: true,
    },
  ];
  return <DataTable columns={columns} rows={rows} rowKey={(r) => r.region} empty={c.draftRowsEmpty} />;
}
