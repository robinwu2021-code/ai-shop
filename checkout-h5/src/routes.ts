/**
 * 收银台的全部页面（唯一真源）。界面清单（`scripts/gen-ui-catalog.py`）从这里读 —— 加页面只改这张表。
 * `title` 是简体中文页名，只给清单用；页面上显示的标题走 i18n 的 `titleKey`。
 */
export interface RouteDef {
  path: string;
  view: "pay" | "code" | "result" | "return";
  titleKey: "title.pay" | "title.code" | "title.result";
  title: string;
}

export const ROUTES: RouteDef[] = [
  { path: "/c/:token", view: "pay", titleKey: "title.pay", title: "付款（动态码）" },
  { path: "/checkout/:payOrderNo", view: "pay", titleKey: "title.pay", title: "付款（托管收银台）" },
  { path: "/q/:code", view: "code", titleKey: "title.code", title: "静态码输金额" },
  { path: "/result/:payOrderNo", view: "result", titleKey: "title.result", title: "付款结果" },
  { path: "/return/:payOrderNo", view: "return", titleKey: "title.result", title: "渠道回跳" },
];

export interface Match {
  route: RouteDef;
  params: Record<string, string>;
  query: URLSearchParams;
}

/** 按路径匹配；参数段只认一个路径段（令牌是 base64url，不含 /） */
export function match(pathname: string, search: string): Match | null {
  const parts = pathname.replace(/\/+$/, "").split("/");
  for (const route of ROUTES) {
    const pat = route.path.split("/");
    if (pat.length !== parts.length) continue;
    const params: Record<string, string> = {};
    let ok = true;
    for (let i = 0; i < pat.length; i++) {
      if (pat[i].startsWith(":")) {
        if (!parts[i]) { ok = false; break; }
        params[pat[i].slice(1)] = decodeURIComponent(parts[i]);
      } else if (pat[i] !== parts[i]) { ok = false; break; }
    }
    if (ok) return { route, params, query: new URLSearchParams(search) };
  }
  return null;
}
