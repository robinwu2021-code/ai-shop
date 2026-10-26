import { api, ApiError, type SessionView } from "../api";
import type { Ctx } from "../app";
import { launch } from "../channel";
import { h, ltr } from "../dom";
import { methodLabel } from "../i18n";
import { formatMoney } from "../money";

/** 01 付款 / 02 跳转渠道（原型 pay-checkout-h5 s01–s02） */
export async function renderPay(ctx: Ctx, token: string): Promise<void> {
  const s = await api.session(token);
  ctx.setMarket(s.market);
  ctx.rememberToken(s.payOrderNo, token);
  if (s.status === "DONE") {
    ctx.go("/result/" + encodeURIComponent(s.payOrderNo), true);
    return;
  }
  ctx.root.ownerDocument.title = ctx.t("title.pay");
  const amount = formatMoney(s.amountMinor, s.currency, s.currencyScale, ctx.lang);
  const status = h("p", { class: "muted", role: "status" });
  const header = payeeBlock(ctx, s);
  if (amount === null) {
    status.textContent = ctx.t("error.amountUnavailable");
    ctx.root.replaceChildren(h("section", { class: "card", "data-view": "pay" }, header, status));
    return;
  }
  if (s.payMethods.length === 0) {
    status.textContent = ctx.t("pay.noMethod");
    ctx.root.replaceChildren(h("section", { class: "card", "data-view": "pay" }, header,
      h("div", { class: "amount" }, ltr(amount)), status));
    return;
  }

  const list = h("fieldset", { class: "methods" }, h("legend", {}, ctx.t("method")),
    ...s.payMethods.map((m, i) => h("label", { class: "method" },
      h("input", { type: "radio", name: "method", value: m, ...(i === 0 ? { checked: "" } : {}) }),
      h("span", {}, methodLabel(ctx.lang, m)))));
  const button = h("button", { class: "primary", type: "button" }, ctx.t("pay.confirm", { amount }));
  const check = h("button", { class: "secondary", type: "button", hidden: "" }, ctx.t("pay.check"));
  check.addEventListener("click", () => ctx.go("/result/" + encodeURIComponent(s.payOrderNo)));

  button.addEventListener("click", async () => {
    const chosen = list.querySelector<HTMLInputElement>("input[name=method]:checked")?.value ?? s.payMethods[0];
    button.disabled = true;
    status.textContent = ctx.t("pay.opening");
    try {
      const q = new URLSearchParams(ctx.win.location.search);
      // 微信 JSAPI 要付款人在该公众号下的 openid：由前一跳的网页授权带在查询串里；没有就由后端按方式判
      const started = await api.pay(token, chosen, q.get("openid") ?? undefined, q.get("appid") ?? undefined);
      const r = await launch(started.payChannel, started.payParams, ctx.win);
      if (r === "test" || r === "invoked") {
        ctx.go("/result/" + encodeURIComponent(s.payOrderNo));
        return;
      }
      if (r === "redirected") {
        check.hidden = false;
        return;
      }
      status.textContent = r === "cancelled" ? "" : ctx.t("pay.unsupported");
      check.hidden = r !== "cancelled";
    } catch (e) {
      if (e instanceof ApiError && e.gone) throw e;
      status.textContent = e instanceof ApiError && e.message ? e.message : ctx.t("pay.failed");
    }
    button.disabled = false;
  });

  const until = new Intl.DateTimeFormat(ctx.lang, { hour: "2-digit", minute: "2-digit" }).format(new Date(s.expireAt));
  ctx.root.replaceChildren(h("section", { class: "card", "data-view": "pay" },
    header,
    h("div", { class: "amount", "data-testid": "amount" }, ltr(amount)),
    list,
    h("p", { class: "muted small" }, ctx.t("pay.validUntil", { time: until })),
    status),
  h("footer", { class: "dock" }, button, check));
}

/** 收款方：商户名 · 门店名 —— 防换贴码（F34），放在金额上方 */
export function payeeBlock(ctx: Ctx, s: Pick<SessionView, "payeeName" | "storeName">): HTMLElement {
  return h("div", { class: "payee" },
    h("span", { class: "muted" }, ctx.t("payee")),
    h("strong", { "data-testid": "payee" }, [s.payeeName, s.storeName].filter(Boolean).join(" · ")));
}
