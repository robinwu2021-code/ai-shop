/**
 * 拿到渠道参数后唤起付款。<b>这里不判成功</b> —— 唤起完成只说明顾客做完了交互，成功与否只看结果接口（F33）。
 */

export type Launch = "redirected" | "invoked" | "cancelled" | "test" | "unsupported";

interface WeixinBridge {
  invoke(name: string, params: Record<string, string>, cb: (r: { err_msg?: string }) => void): void;
}

const REDIRECT_KEYS = ["h5Url", "mwebUrl", "mweb_url", "payUrl", "redirectUrl"] as const;
const JSAPI_KEYS = ["appId", "timeStamp", "nonceStr", "package", "signType", "paySign"] as const;

export async function launch(channel: string, params: Record<string, string>,
                             win: Window = window): Promise<Launch> {
  if (channel === "TEST" || params.testChannel === "true" || params.prepayId?.startsWith("test_")) {
    return "test";
  }
  if (JSAPI_KEYS.every((k) => params[k])) {
    const bridge = (win as unknown as { WeixinJSBridge?: WeixinBridge }).WeixinJSBridge;
    if (!bridge) return "unsupported";
    const jsapi = Object.fromEntries(JSAPI_KEYS.map((k) => [k, params[k]]));
    return new Promise((resolve) => bridge.invoke("getBrandWCPayRequest", jsapi, (r) =>
      resolve(/cancel/i.test(r.err_msg ?? "") ? "cancelled" : "invoked")));
  }
  for (const k of REDIRECT_KEYS) {
    const url = params[k];
    // 只跳 https：渠道给的地址之外，任何别的协议（javascript: 之类）都不碰
    if (url && /^https:\/\//i.test(url)) {
      win.location.assign(url);
      return "redirected";
    }
  }
  return "unsupported";
}
