// 进销存（P-18）的内存 mock。三个都只读。
import type { InvBalanceRow, InvHealthRow, InvLedgerRow } from "@/lib/types";
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
  /** 某一个商家的库存待办。形状照 `BalanceVO`，不照界面拟 */
  listInvBalances: () =>
    wait<InvBalanceRow[]>([
      { itemId: "ITM0001", name: "东北大米", specText: "5斤装", baseUom: "袋",
        onHand: -3, reserved: 0, available: -3, safetyStock: 10,
        lastMovedAt: "2026-08-26T14:22:00", flags: ["SHORTAGE"] },
      { itemId: "ITM0003", name: "陈醋", specText: "500ml", baseUom: "瓶",
        onHand: 24, reserved: 0, available: 24, safetyStock: null,
        lastMovedAt: "2026-05-26T09:00:00", flags: ["STALE"] },
    ]),

  listInvHealth: (q = {}) =>
    wait(mockInvHealth.filter((r) => !q.kind || r.kind === q.kind).slice(0, q.limit ?? 200)),

  /*
   * **形状要与后端逐字一致**（`LedgerPageVO{entries,nextCursor}`）。
   * 这一条是这次踩过的坑：mock 按前端自己拟的形状写，于是 mock 自查里两边自洽，
   * 而接上真后端才发现对不上 —— mock 的价值全在「它替身的是真的那个」。
   */
  listInvLedger: (q) => {
    const rows = mockInvLedger.filter((r) => !q.cursor || r.id < q.cursor)
      .slice(0, q.size ?? 50);
    const last = rows[rows.length - 1];
    return wait({
      entries: rows,
      nextCursor: rows.length < (q.size ?? 50) ? null : (last?.id ?? null),
    });
  },

  getInvRecon: () =>
    wait({
      scannedSkus: 216,
      moved: 0,
      skipped: 214,
      // 刻意留两个没搬：没搬的和有差异的是**两种**不合格，界面要分得开
      pending: 2,
      // **故意不干净**：全绿的样子谁都想得出来，不干净时怎么读才是这一页的价值
      clean: false,
      diffs: [
        // 刻意造两种差异：一条实存对不上、一条**只有预留对不上**
        { entityNo: "M0001", storeNo: "S0001", skuNo: "SK0003",
          platformQty: 5, inventoryQty: 3, platformHeld: 0, inventoryHeld: 0 },
        { entityNo: "M0002", storeNo: "S0002", skuNo: "SK0007",
          platformQty: 20, inventoryQty: 20, platformHeld: 5, inventoryHeld: 0 },
      ],
    }),

  /*
   * 三把钥匙，**刻意各是一种状态**：一把在用、一把从没被用过、一把已吊销。
   * 三把都 ACTIVE 的话，「lastUsedAt 空着该怎么显示」「吊销了长什么样」
   * 这两件事在 mock 上永远看不见 —— 而它们正是这一屏要回答的。
   */
  listInvCredentials: async () => [
    { credentialId: "CRED-0001", appKey: "ak_7f3c9e21", name: "某某 ERP（生产）",
      scopes: "read,stock:sync", status: "ACTIVE",
      expiresAt: "2027-01-31T00:00:00", lastUsedAt: "2026-08-29T09:12:00",
      createdAt: "2026-06-01T10:00:00" },
    { credentialId: "CRED-0002", appKey: "ak_2b8d0a55", name: "对接联调（临时）",
      scopes: "read", status: "ACTIVE",
      expiresAt: null, lastUsedAt: null, createdAt: "2026-08-20T15:30:00" },
    { credentialId: "CRED-0003", appKey: "ak_91ee47c0", name: "老服务商（已换）",
      scopes: "read", status: "REVOKED",
      expiresAt: null, lastUsedAt: "2026-05-11T08:03:00", createdAt: "2026-02-14T09:00:00" },
  ],

  issueInvCredential: async () => ({
    credentialId: "CRED-NEW",
    appKey: "ak_" + Math.random().toString(16).slice(2, 10),
    // mock 里也给一段像样的：太短的话，「这串要当场复制走」这件事在 mock 上不成立
    appSecret: "sk_" + Math.random().toString(16).slice(2) + Math.random().toString(16).slice(2),
  }),

  revokeInvCredential: async () => undefined,
};
