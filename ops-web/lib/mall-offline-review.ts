import type { MallOfflineActionView, MallOfflineConfirmRequest, MallOfflineVoidRequest, MallOfflineSupplementRequest } from "./types";
import type { CommerceApi } from "./api/contracts/commerce";

/** Missing frozen order data must block confirmation, including an older backend response. */
export function mallOfflineReceiver(instructions: { platformMerchantNo?: string | null }): string | null {
  const receiver = instructions.platformMerchantNo;
  return typeof receiver === "string" && receiver.trim() ? receiver : null;
}

type Review = { kind: "confirm"; request: MallOfflineConfirmRequest } | { kind: "void"; request: MallOfflineVoidRequest } | { kind:"supplement";request:MallOfflineSupplementRequest };

/** One operator intent, retained only for this mounted form. Never put bank/evidence data in storage. */
export class MallOfflineReviewAttempt {
  private readonly intent: Review;
  phase: "ready" | "unknown" | "accepted" | "absent" | "done" = "ready";
  constructor(intent: Review) {
    this.intent = intent.kind === "confirm"
      ? { kind: "confirm", request: Object.freeze({ ...intent.request, actualAmount: Object.freeze({ ...intent.request.actualAmount }) }) }
      : intent.kind === "supplement" ? {kind:"supplement",request:Object.freeze({...intent.request,evidenceKeys:Object.freeze([...intent.request.evidenceKeys]) as unknown as string[]})}
      : { kind: "void", request: Object.freeze({ ...intent.request,
        unmatchedDispositions: Object.freeze({ ...intent.request.unmatchedDispositions }) }) };
  }
  private outcome(result: MallOfflineActionView) {
    this.phase = result.status === "ACCEPTED" ? "accepted" : "done";
    return result;
  }
  async submit(api: Pick<CommerceApi, "confirmOfflinePayment" | "voidOfflineOrder"> & Partial<Pick<CommerceApi,"supplementOfflineReceipt">>, storeNo: string, orderNo: string) {
    if (this.phase !== "ready" && this.phase !== "absent") throw new Error("LOOKUP_REQUIRED");
    // A thrown response may follow a committed action. Only a read-only lookup can enable retry.
    this.phase = "unknown";
    return this.outcome(await (this.intent.kind === "confirm"
      ? api.confirmOfflinePayment(storeNo, orderNo, this.intent.request)
      : this.intent.kind === "supplement" ? api.supplementOfflineReceipt!(storeNo,orderNo,this.intent.request) : api.voidOfflineOrder(storeNo, orderNo, this.intent.request)));
  }
  async resolve(lookup: (actionNo: string) => Promise<MallOfflineActionView | null>) {
    const result = await lookup(this.intent.request.actionNo);
    if (result) return this.outcome(result);
    // Once ACCEPTED was observed, an absent read must never authorize a new submission.
    if (this.phase !== "accepted") this.phase = "absent";
    return null;
  }
}
