import { api, ApiError } from "./api";
import { h } from "./dom";
import { isRtl, pickLang, t, type Key, type Lang } from "./i18n";
import { match } from "./routes";
import { renderCode } from "./views/code";
import { renderPay } from "./views/pay";
import { renderResult } from "./views/result";

/** 页面上下文：语言随第一次拿到的市场定下（浏览器语言优先），之后整个会话不变 */
export interface Ctx {
  root: HTMLElement;
  lang: Lang;
  t(key: Key, vars?: Record<string, string>): string;
  go(path: string, replace?: boolean): void;
  setMarket(market: string | null | undefined): void;
  rememberToken(payOrderNo: string, token: string): void;
  tokenOf(payOrderNo: string): string | null;
  win: Window;
}

const TOKEN_KEY = (no: string) => "co:" + no;

export function createApp(root: HTMLElement, win: Window = window): Ctx {
  // ?lang= 优先（商户印码时可指定语言）；其次浏览器语言
  const forced = new URLSearchParams(win.location.search).get("lang");
  const nav = win.navigator.languages?.length ? win.navigator.languages : [win.navigator.language ?? "en"];
  const browser = forced ? [forced, ...nav] : nav;
  const ctx: Ctx = {
    root,
    lang: pickLang(browser, null),
    t: (key, vars) => t(ctx.lang, key, vars),
    go(path, replace = false) {
      if (replace) win.history.replaceState(null, "", path);
      else win.history.pushState(null, "", path);
      void render(ctx);
    },
    setMarket(market) {
      ctx.lang = pickLang(browser, market);
      applyLang(ctx);
    },
    // 渠道回跳页没有令牌（令牌不进渠道的回跳地址）：同一个浏览器标签页里记一下，只存在 sessionStorage
    rememberToken(no, token) {
      try { win.sessionStorage.setItem(TOKEN_KEY(no), token); } catch { /* 隐私模式：结果页退化为「回原页面查看」 */ }
    },
    tokenOf(no) {
      try { return win.sessionStorage.getItem(TOKEN_KEY(no)); } catch { return null; }
    },
    win,
  };
  applyLang(ctx);
  win.addEventListener("popstate", () => void render(ctx));
  return ctx;
}

function applyLang(ctx: Ctx) {
  const html = ctx.root.ownerDocument.documentElement;
  html.lang = ctx.lang;
  html.dir = isRtl(ctx.lang) ? "rtl" : "ltr";
}

export async function render(ctx: Ctx): Promise<void> {
  const loc = ctx.win.location;
  const m = match(loc.pathname, loc.search);
  if (!m) return renderExpired(ctx);
  try {
    switch (m.route.view) {
      case "pay": {
        const token = m.params.token ?? m.query.get("t");
        return token ? await renderPay(ctx, token) : renderExpired(ctx);
      }
      case "code":
        return await renderCode(ctx, m.params.code);
      case "return":
        // 渠道回跳只是落地点：换成结果页地址再渲染，刷新与分享都落在结果页
        ctx.win.history.replaceState(null, "", "/result/" + encodeURIComponent(m.params.payOrderNo));
        return await renderResult(ctx, m.params.payOrderNo, null);
      case "result":
        return await renderResult(ctx, m.params.payOrderNo, m.query.get("t"));
    }
  } catch (e) {
    if (e instanceof ApiError && e.gone) return renderExpired(ctx);
    return renderError(ctx, e);
  }
}

export function renderExpired(ctx: Ctx): void {
  ctx.root.ownerDocument.title = ctx.t("expired.title");
  ctx.root.replaceChildren(h("section", { class: "card center", "data-view": "expired" },
    h("h1", { class: "big" }, ctx.t("expired.title")),
    h("p", { class: "muted" }, ctx.t("expired.body"))));
}

function renderError(ctx: Ctx, e: unknown): void {
  ctx.root.replaceChildren(h("section", { class: "card center", "data-view": "error" },
    h("p", { class: "muted" }, e instanceof ApiError && e.message && e.status > 0 ? e.message : ctx.t("error.network"))));
}

export { api };
