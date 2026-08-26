// 覆盖范围：进销存（P-18）。三个都只读。
import type { InvHealthRow, InvLedgerRow, InvReconReport } from "@/lib/types";
import { client } from "../http-client";
import type { InventoryApi } from "../contracts/inventory";

export const inventoryHttp: InventoryApi = {
  listInvHealth: (q = {}) =>
    client.get<InvHealthRow[]>("/ops/inventory/health", {
      kind: q.kind, limit: q.limit ?? 200,
    }),

  /** 游标传上一页最后一行的 `id`；**不是页码** —— 时间游标会因时钟回拨漏行 */
  listInvLedger: (q) =>
    client.get<InvLedgerRow[]>("/ops/inventory/ledger", {
      ownerId: q.ownerId, itemId: q.itemId, cursor: q.cursor, size: q.size ?? 50,
    }),

  getInvRecon: (q = {}) =>
    client.get<InvReconReport>("/ops/inventory/recon", { limit: q.limit ?? 500 }),
};
