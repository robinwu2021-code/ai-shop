// 进销存（P-18）的内存 mock。三个都只读。
import type { InvHealthRow, InvLedgerRow } from "@/lib/types";
import type { InventoryApi } from "../contracts/inventory";
import { wait } from "./_wait";

/*
 * 库存治理的假数据。
 *
 * **三条都刻意「不干净」**：健康度页要有东西看，对差要有一条差异 ——
 * 空页面看不出「这一页是干什么的」，而全绿的对差看不出「不干净时长什么样」。
 * 真接上后端之后这批数据就不再出现。
 */
const mockInvHealth: InvHealthRow[] = [
  { kind: "NEGATIVE", entityNo: "M0001", merchantName: "张记粮油", storeNo: "S0001",
    itemId: "ITM0001", itemName: "东北大米", specText: "5斤装", onHand: -3, reserved: 0, available: -3 },
  { kind: "ZERO_ON_SALE", entityNo: "M0002", merchantName: "李家果蔬", storeNo: "S0002",
    itemId: "ITM0002", itemName: "蓝莓", specText: "125g", onHand: 0, reserved: 0, available: 0 },
  { kind: "STALE", entityNo: "M0001", merchantName: "张记粮油", storeNo: "S0001",
    itemId: "ITM0003", itemName: "陈醋", specText: "500ml", onHand: 24, reserved: 0, available: 24, idleDays: 92 },
];

const mockInvLedger: InvLedgerRow[] = [
  { id: 8812345, docKind: "OUT", docNo: "OUT-2408260031", reasonCode: "SALE",
    qtyDelta: -2, balanceAfter: 3, unitCostMinor: 4200, occurredAt: "2026-08-26T14:22:00", operator: "系统" },
  { id: 8812344, docKind: "OUT", docNo: "CNT-24082601", reasonCode: "COUNT_LOSS",
    qtyDelta: -1, balanceAfter: 5, occurredAt: "2026-08-26T09:10:00", operator: "张伟" },
  { id: 8812343, docKind: "IN", docNo: "IN-24082502", reasonCode: "PURCHASE",
    qtyDelta: 20, balanceAfter: 6, unitCostMinor: 4200, occurredAt: "2026-08-25T18:40:00", operator: "老板" },
];

export const inventoryMock: InventoryApi = {
  listInvHealth: (q = {}) =>
    wait(mockInvHealth.filter((r) => !q.kind || r.kind === q.kind).slice(0, q.limit ?? 200)),

  listInvLedger: (q) =>
    // 游标是 id：传了就取比它小的（倒序翻页）
    wait(mockInvLedger.filter((r) => !q.cursor || r.id < q.cursor).slice(0, q.size ?? 50)),

  getInvRecon: () =>
    wait({
      scannedSkus: 216,
      moved: 0,
      skipped: 216,
      // **故意不干净**：全绿的样子谁都想得出来，不干净时怎么读才是这一页的价值
      clean: false,
      diffs: [
        { entityNo: "M0001", storeNo: "S0001", skuNo: "SK0003", platformQty: 5, inventoryQty: 3 },
      ],
    }),
};
