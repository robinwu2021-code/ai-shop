import { api, type Outcome, type SessionView } from "../api";
import type { Ctx } from "../app";
import { h, ltr } from "../dom";
import { formatMoney } from "../money";
import { payeeBlock } from "./pay";

/** 自动刷新：每 2 秒一次，最多 30 次（约一分钟）；之后停在「处理中」，不无限轮询 */
export const POLL_MS = 2000;
export const POLL_MAX = 30;

/** 03 结果（原型 s03）：只有成功 / 处理中 / 失败；不入账（F33） */
export async function renderResult(ctx: Ctx, payOrderNo: string, tokenFromUrl: string | null,
                                   sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)))
    : Promise<void> {
  ctx.root.ownerDocument.title = ctx.t("title.result");
  const token = tokenFromUrl ?? ctx.tokenOf(payOrderNo);
  if (!token) {
    ctx.root.replaceChildren(card("unknown", ctx.t("result.unknown")));
    return;
  }
  let outcome: Outcome = (await api.result(token)).result;
  // 收款方与金额只是给顾客对一眼：取不到（单已关、令牌已作废）就只显示结果，不报错
  const detail = await api.session(token).catch(() => null);
  for (let i = 0; outcome === "PROCESSING" && i < POLL_MAX; i++) {
    show(ctx, outcome, token, detail);
    await sleep(POLL_MS);
    if (!ctx.root.isConnected) return;
    outcome = (await api.result(token)).result;
  }
  show(ctx, outcome, token, detail);
}

function show(ctx: Ctx, outcome: Outcome, token: string, detail: SessionView | null) {
  const text = outcome === "SUCCEEDED" ? ctx.t("result.succeeded")
    : outcome === "FAILED" ? ctx.t("result.failed") : ctx.t("result.processing");
  const node = card(outcome.toLowerCase(), text);
  if (detail) {
    const amount = formatMoney(detail.amountMinor, detail.currency, detail.currencyScale, ctx.lang);
    node.append(payeeBlock(ctx, detail));
    if (amount) node.append(h("div", { class: "amount small-amount" }, ltr(amount)));
  }
  if (outcome === "FAILED") {
    const retry = h("button", { class: "secondary", type: "button" }, ctx.t("result.retry"));
    retry.addEventListener("click", () => ctx.go("/c/" + encodeURIComponent(token)));
    node.append(retry);
  }
  ctx.root.replaceChildren(node);
}

function card(state: string, text: string): HTMLElement {
  return h("section", { class: "card center", "data-view": "result", "data-state": state },
    h("h1", { class: "big" }, text));
}
