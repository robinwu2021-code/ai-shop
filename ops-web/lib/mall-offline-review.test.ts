import { describe, expect, it } from "vitest";
import { MallOfflineReviewAttempt, mallOfflineReceiver } from "./mall-offline-review";
import type { MallOfflineConfirmRequest, MallOfflineActionView } from "./types";

const request = (): MallOfflineConfirmRequest => ({ actionNo: "original", claimNo: null,
  actualAmount: { currency: "TWD", minor: "69900" }, actualPaidAt: "2026-11-01T00:00:00.000Z",
  actualReceiver: "frozen", bankReference: "bank", evidenceKey: "proof", historical: false, reason: "checked" });

describe("mall offline review recovery", () => {
  it("requires a frozen order receiver and never substitutes a typed receiver", () => {
    expect(mallOfflineReceiver({})).toBeNull();
    expect(mallOfflineReceiver({ platformMerchantNo: " " })).toBeNull();
    expect(mallOfflineReceiver({ platformMerchantNo: "frozen-order" })).toBe("frozen-order");
  });

  it("retains the original action and exact private payload only in memory after an unknown result", async () => {
    const payload = request();
    const attempt = new MallOfflineReviewAttempt({ kind: "confirm", request: payload });
    const sent: MallOfflineConfirmRequest[] = [];
    const api = { confirmOfflinePayment: async (_s: string, _o: string, p: MallOfflineConfirmRequest) => {
      sent.push(p); if (sent.length === 1) throw new Error("response lost");
      return { actionNo: p.actionNo, status: "APPLIED", paymentNo: "payment" } as MallOfflineActionView;
    }, voidOfflineOrder: async () => { throw new Error("wrong operation"); } };
    await expect(attempt.submit(api, "store", "order")).rejects.toThrow("response lost");
    payload.bankReference = "edited"; payload.actualAmount.minor = "1";
    expect(attempt.phase).toBe("unknown");
    await expect(attempt.submit(api, "store", "order")).rejects.toThrow("LOOKUP_REQUIRED");
    expect(sent).toHaveLength(1);
    expect(await attempt.resolve(async () => null)).toBeNull();
    await attempt.submit(api, "store", "order");
    expect(sent[1]).toBe(sent[0]);
    expect(sent[1]).toEqual(request());
    expect(attempt.phase).toBe("done");
  });

  it("keeps ACCEPTED pending and resolves APPLIED or UNMATCHED by read-only lookup", async () => {
    const attempt = new MallOfflineReviewAttempt({ kind: "confirm", request: request() });
    const result = { actionNo: "original", status: "ACCEPTED", paymentNo: null } as MallOfflineActionView;
    await attempt.submit({ confirmOfflinePayment: async () => result, voidOfflineOrder: async () => result }, "s", "o");
    expect(attempt.phase).toBe("accepted");
    await expect(attempt.submit({ confirmOfflinePayment: async () => result, voidOfflineOrder: async () => result }, "s", "o"))
      .rejects.toThrow("LOOKUP_REQUIRED");
    expect(await attempt.resolve(async () => ({ ...result, status: "UNMATCHED", paymentNo: "unmatched" })))
      .toEqual({ ...result, status: "UNMATCHED", paymentNo: "unmatched" });
    expect(attempt.phase).toBe("done");
  });

  it("freezes the original reconciliation and dispositions for void retries", async () => {
    const payload = { actionNo: "void-original", reconciliationReference: "bank-recon", reason: "checked", evidenceKey: "proof",
      unmatchedDispositions: { payment: "returned" } };
    const attempt = new MallOfflineReviewAttempt({ kind: "void", request: payload });
    payload.unmatchedDispositions.payment = "changed";
    const result = { actionNo: "void-original", status: "APPLIED", paymentNo: null } as MallOfflineActionView;
    const seen: unknown[] = [];
    await attempt.submit({ confirmOfflinePayment: async () => { throw new Error("wrong operation"); },
      voidOfflineOrder: async (_s, _o, p) => { seen.push(p); return result; } }, "s", "o");
    expect(seen).toEqual([{ ...payload, unmatchedDispositions: { payment: "returned" } }]);
  });
});
