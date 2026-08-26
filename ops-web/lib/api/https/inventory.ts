// 覆盖范围：进销存（P-18）。三个都只读。
import type { InvHealthRow, InvLedgerPage, InvReconReport } from "@/lib/types";
import { client } from "../http-client";
import type { InventoryApi } from "../contracts/inventory";

export const inventoryHttp: InventoryApi = {
  listInvHealth: (q = {}) =>
    client.get<InvHealthRow[]>("/ops/inventory/health", {
      kind: q.kind, limit: q.limit ?? 200,
    }),

  /** 游标传上一页的 `nextCursor`；**不是页码**，也不自己拿最后一行的 id 去推 */
  listInvLedger: (q) =>
    client.get<InvLedgerPage>("/ops/inventory/ledger", {
      entityNo: q.entityNo, itemId: q.itemId, cursor: q.cursor, size: q.size ?? 50,
    }),

  getInvRecon: (q = {}) =>
    client.get<InvReconReport>("/ops/inventory/recon", { limit: q.limit ?? 500 }),
};
