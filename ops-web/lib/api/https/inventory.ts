// 覆盖范围：进销存（P-18）。**库存本身只读**；开放对接的钥匙是唯一的写口，
// 它改的不是货，是「谁能读这些货」。
import type { InvLinkHealth, InvBalanceRow, InvCredential, InvCredentialIssued, InvHealthRow, InvLedgerPage, InvReconReport } from "@/lib/types";
import { client } from "../http-client";
import type { InventoryApi } from "../contracts/inventory";

export const inventoryHttp: InventoryApi = {
  listInvHealth: (q = {}) =>
    client.get<InvHealthRow[]>("/ops/inventory/health", {
      kind: q.kind, limit: q.limit ?? 200,
    }),
  invLinkHealth: () => client.get<InvLinkHealth[]>("/ops/inventory/link-health"),

  listInvBalances: (q) =>
    client.get<InvBalanceRow[]>("/ops/inventory/balances", {
      entityNo: q.entityNo, type: q.type ?? "todo", size: q.size ?? 100,
    }),

  /** 游标传上一页的 `nextCursor`；**不是页码**，也不自己拿最后一行的 id 去推 */
  listInvLedger: (q) =>
    client.get<InvLedgerPage>("/ops/inventory/ledger", {
      entityNo: q.entityNo, itemId: q.itemId, cursor: q.cursor, size: q.size ?? 50,
    }),

  getInvRecon: (q = {}) =>
    client.get<InvReconReport>("/ops/inventory/recon", { limit: q.limit ?? 500 }),

  listInvCredentials: (q) =>
    client.get<InvCredential[]>("/ops/inventory/credentials", { entityNo: q.entityNo }),

  issueInvCredential: (body) =>
    client.post<InvCredentialIssued>("/ops/inventory/credentials", body),

  revokeInvCredential: (credentialId) =>
    client.post<void>(`/ops/inventory/credentials/${credentialId}/revoke`),
};
