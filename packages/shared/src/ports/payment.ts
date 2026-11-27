// 端能力：支付。一期只接微信支付（小程序 JSAPI / App SDK）。
// ⚠️ 端侧不写死任何 PSP 参数，也不自判成功 —— 支付结果一律以后端回调为准。
export interface PayParams {
  /** 后端下单返回的支付参数（微信 JSAPI 五件套等） */
  [k: string]: unknown;
}

export interface PayResult {
  /** 端侧唤起结果，仅表示「用户完成了交互」，不代表支付成功 */
  invoked: boolean;
  cancelled: boolean;
  /**
   * 唤起失败时**通道自己说的原因**（`wx.requestPayment` 的 errMsg）。
   *
   * <b>此前这个字段不存在，于是失败被静默吞掉</b>：页面只处理了「用户取消」，
   * 非取消的失败一句话都不留，继续往下走。
   * 而微信在这一步会说很具体的话（未开通支付、appid 不匹配、签名错、参数缺失），
   * 那句话是排查这条链唯一的输入 —— 丢掉它，剩下的只有「点了没反应」。
   */
  failReason?: string;
}

export async function requestPayment(params: PayParams): Promise<PayResult> {
  // #ifdef MP-WEIXIN || APP-PLUS
  return new Promise((resolve) => {
    uni.requestPayment({
      provider: "wxpay",
      ...(params as object),
      success: () => resolve({ invoked: true, cancelled: false }),
      fail: (e) => {
        const msg = e?.errMsg || "";
        // 取消是正常路径；其余一律带上通道原话
        const cancelled = /cancel/i.test(msg);
        if (!cancelled) {
          // 控制台留一份：真机上 toast 一闪而过，而这句话往往是唯一的线索
          console.error("[pay] requestPayment 失败：", msg, e);
        }
        resolve({ invoked: false, cancelled, failReason: cancelled ? undefined : msg });
      },
    } as UniApp.RequestPaymentOptions);
  });
  // #endif

  // #ifdef H5
  return redirectToCheckout(params);
  // #endif
}

/** 渠道给的「跳去付款」地址常见的几个键名（near-pay 按渠道原样透传，LINE Pay 是 paymentUrl.web）。 */
const REDIRECT_KEYS = ["paymentUrl", "payUrl", "redirectUrl", "checkoutUrl", "cashierUrl", "webUrl", "h5Url", "mwebUrl", "url"];

/** 从支付参数里找跳转地址：只认 http(s)，支持一层嵌套（如 paymentUrl.web）。 */
export function checkoutUrlOf(params: PayParams): string | undefined {
  const isUrl = (v: unknown): v is string => typeof v === "string" && /^https?:\/\//i.test(v);
  for (const key of REDIRECT_KEYS) {
    const v = params[key];
    if (isUrl(v)) return v;
    if (v && typeof v === "object") {
      const nested = v as Record<string, unknown>;
      for (const k of ["web", "h5", "url", "app"]) if (isUrl(nested[k])) return nested[k] as string;
    }
  }
  return undefined;
}

/**
 * 表单指令（near-pay 对蓝新等渠道回 `{instructionType: "FORM_POST", action, fieldsJson}`，
 * fieldsJson 是字段名到值的 JSON 字符串）。认不出就返回 undefined。
 */
export function formPostOf(params: PayParams): { action: string; fields: Record<string, string> } | undefined {
  if (params["instructionType"] !== "FORM_POST") return undefined;
  const action = params["action"];
  if (typeof action !== "string" || !/^https:\/\//i.test(action)) return undefined;
  const raw = params["fieldsJson"];
  let fields: unknown = raw;
  if (typeof raw === "string") {
    try {
      fields = JSON.parse(raw);
    } catch {
      return undefined;
    }
  }
  if (!fields || typeof fields !== "object") return undefined;
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(fields as Record<string, unknown>)) out[k] = v == null ? "" : String(v);
  return { action, fields: out };
}

/**
 * 网页端（H5）付款：跳去渠道付款页（LINE Pay 等回 redirectUrl），或把表单提交给渠道（蓝新回 FORM_POST）。
 * 跳走即表示「用户完成了交互」，付没付成以后端为准（付款页回来后轮询 pay-result，后端会向支付服务确认）。
 * 两样都没有，说明这个付款方式不能在网页上付。
 */
function redirectToCheckout(params: PayParams): PayResult {
  const url = checkoutUrlOf(params);
  if (url) {
    window.location.href = url;
    return { invoked: true, cancelled: false };
  }
  const post = formPostOf(params);
  if (post) {
    const form = document.createElement("form");
    form.method = "POST";
    form.action = post.action;
    form.style.display = "none";
    for (const [name, value] of Object.entries(post.fields)) {
      const input = document.createElement("input");
      input.type = "hidden";
      input.name = name;
      input.value = value;
      form.appendChild(input);
    }
    document.body.appendChild(form);
    form.submit();
    return { invoked: true, cancelled: false };
  }
  return { invoked: false, cancelled: false, failReason: "这个付款方式不支持在网页上付款" };
}
