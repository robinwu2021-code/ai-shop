// 覆盖范围：支付管理（P-4.2）。
import { client } from "../http-client";
import type { ReconAxisReport, ReconCoverage, ReconDiff, Page } from "@/lib/types";
import type { PaymentApi } from "../contracts/payment";

/** Backend financial names remain explicit here; pages use the existing display contract. */
interface ReconDiffResponse extends Omit<ReconDiff, "channel" | "type" | "channelAmount" | "platformAmount" | "createdAt" | "resolvedAt"> {
  payChannel: ReconDiff["channel"];
  diffType: ReconDiff["type"];
  channelAmountMinor: number;
  platformAmountMinor: number;
  createdAt: number;
  resolvedAt?: number | null;
}
function reconDiff(row: ReconDiffResponse): ReconDiff {
  return {
    ...row,
    channel: row.payChannel,
    type: row.diffType,
    channelAmount: row.channelAmountMinor,
    platformAmount: row.platformAmountMinor,
    createdAt: new Date(row.createdAt).toISOString(),
    resolvedAt: row.resolvedAt == null ? null : new Date(row.resolvedAt).toISOString(),
  };
}

export const paymentHttp: PaymentApi = {
  listReconDiffs: async (q) => {
    const page = await client.get<Page<ReconDiffResponse>>("/ops/payments/recon-diffs", q);
    return { ...page, records: page.records.map(reconDiff) };
  },
  reconCoverage: () => client.get<ReconCoverage>("/ops/payments/recon-coverage"),
  reconAxes: () => client.get<ReconAxisReport[]>("/ops/payments/recon-axes"),
  channelMessages: (q) => client.get("/ops/channel-messages", q),
  resolveReconDiff: async (v) => reconDiff(await client.post<ReconDiffResponse>(`/ops/payments/recon-diffs/${v.diffNo}/resolve`, v)),
  ignoreReconDiff: async (v) => reconDiff(await client.post<ReconDiffResponse>(`/ops/payments/recon-diffs/${v.diffNo}/ignore`, v)),
  getCloseRule: () => client.get("/ops/payments/close-rule"),
  saveCloseRule: (v) => client.put("/ops/payments/close-rule", v),
};
