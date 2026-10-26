import { api, ApiError } from "../api";
import type { Ctx } from "../app";
import { h } from "../dom";
import { formatMoney, parseAmount } from "../money";
import { payeeBlock } from "./pay";

/** 04 静态码输金额（原型 s04）：只提示单笔上限；日累计是风控额度，不展示 */
export async function renderCode(ctx: Ctx, qrCode: string): Promise<void> {
  const c = await api.code(qrCode);
  ctx.setMarket(c.market);
  ctx.root.ownerDocument.title = ctx.t("title.code");
  const limit = formatMoney(c.singleLimitMinor, c.currency, c.currencyScale, ctx.lang);
  const status = h("p", { class: "muted", role: "status" });
  if (limit === null || c.currencyScale == null) {
    status.textContent = ctx.t("error.amountUnavailable");
    ctx.root.replaceChildren(h("section", { class: "card", "data-view": "code" }, payeeBlock(ctx, c), status));
    return;
  }
  const scale = c.currencyScale;
  const input = h("input", { class: "amount-input", inputmode: scale === 0 ? "numeric" : "decimal",
    autocomplete: "off", "aria-label": ctx.t("code.amount"), dir: "ltr", "data-testid": "amount-input" });
  const next = h("button", { class: "primary", type: "button" }, ctx.t("code.next"));
  next.addEventListener("click", async () => {
    const minor = parseAmount(input.value, scale);
    if (minor === null || minor > c.singleLimitMinor) {
      status.textContent = minor === null ? ctx.t("code.invalid") : ctx.t("code.overLimit", { amount: limit });
      status.classList.add("error");
      input.setAttribute("aria-invalid", "true");
      return;
    }
    status.classList.remove("error");
    input.removeAttribute("aria-invalid");
    next.disabled = true;
    try {
      const issued = await api.orderByCode(qrCode, minor);
      ctx.rememberToken(issued.payOrderNo, issued.token);
      ctx.go("/c/" + encodeURIComponent(issued.token));
      return;
    } catch (e) {
      status.textContent = e instanceof ApiError && e.status === 404 ? ctx.t("code.disabled")
        : e instanceof ApiError && e.message ? e.message : ctx.t("error.network");
      status.classList.add("error");
    }
    next.disabled = false;
  });
  ctx.root.replaceChildren(h("section", { class: "card", "data-view": "code" },
    payeeBlock(ctx, c),
    h("label", { class: "field" }, h("span", { class: "muted" }, ctx.t("code.amount")), input),
    h("p", { class: "muted small", "data-testid": "limit" }, ctx.t("code.single", { amount: limit })),
    status),
  h("footer", { class: "dock" }, next));
}
