// 端能力：触达。小程序=订阅消息（一次性授权，必须在关键节点收集）；
// App=推送通道（uni-push 2.0，底座是个推，免费档起步 —— ADR-018）。

/**
 * 小程序订阅消息模板 id。
 *
 * <p>默认值与后端桩（StubWxSubscribeGateway）的记账键一致 —— 桩世界里前端收集、
 * 后端扣减用同一套假模板号，链路闭环可测。上线前两端各配真实模板号：
 * 这里走 VITE_WX_TPL_*，后端走 shop.wx.templates.*，**两边必须是同一个 id**，
 * 否则前端攒的额度后端查不到，一条都发不出。
 */
export const SUBSCRIBE_TMPL = {
  arrived: (import.meta.env?.VITE_WX_TPL_ARRIVED as string) || "STUB_TPL_ORDER_ARRIVED",
  refunded: (import.meta.env?.VITE_WX_TPL_REFUNDED as string) || "STUB_TPL_REFUNDED",
} as const;

export interface SubscribeResult {
  /** 用户点了「允许」的模板（每个 = 后端一次发送额度） */
  accepted: string[];
  /** 点了「拒绝」的模板（也要上报：后端记下来才不会反复弹窗） */
  rejected: string[];
}

/**
 * 在关键节点收集订阅授权（支付成功页、开团页）。
 * 小程序限制：必须由用户点击行为触发，且是一次性授权（一次「允许」只够发一条）。
 *
 * <p><b>只收集，不上报</b> —— 上报走各端自己的 api 层（shared 不依赖任何一端的 http），
 * 调用方拿到结果后必须把 accepted / rejected 各报一次 `/mp/message/subscribe`。
 */
export function requestSubscribe(tmplIds: string[]): Promise<SubscribeResult> {
  // #ifdef MP-WEIXIN
  return new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds,
      success: (res) => {
        // 微信按模板逐个给结果：'accept' / 'reject' / 'ban'（被封禁的当拒绝处理）。
        // 类型声明里没有按模板名的索引签名，实际响应有 —— 以运行时形状为准
        const byTmpl = res as unknown as Record<string, string>;
        resolve({
          accepted: tmplIds.filter((id) => byTmpl[id] === "accept"),
          rejected: tmplIds.filter((id) => byTmpl[id] && byTmpl[id] !== "accept"),
        });
      },
      // 弹窗失败（总开关关闭等）不阻塞主流程，也没有可报的结果
      fail: () => resolve({ accepted: [], rejected: [] }),
    });
  });
  // #endif

  // #ifndef MP-WEIXIN
  // H5/App 没有订阅消息这回事；App 的推送走 initPush
  return Promise.resolve({ accepted: [], rejected: [] });
  // #endif
}

/** 后端 msg_push_token.platform 的取值。**与后端 MsgPushToken 的常量逐字一致**。 */
export type PushPlatform = "APP_ANDROID" | "APP_IOS";

export interface PushDevice {
  platform: PushPlatform;
  /** uni-push 的 clientId，即个推 cid */
  clientId: string;
}

/**
 * 取本机推送标识。**只在 App 构建下有值**（H5/小程序返回 null）。
 *
 * <p>拿到之后由各端上报 `/mp/push-token` 或 `/biz/push-token` —— 同 requestSubscribe，
 * shared 只负责取端能力，不碰任何一端的 http。
 *
 * <p>失败返回 null 而不是抛：推送是加速通道，取不到 clientId 不该让登录流程失败，
 * 用户照样能在消息中心看到全部通知。
 */
export function getPushDevice(): Promise<PushDevice | null> {
  // #ifdef APP-PLUS
  return new Promise((resolve) => {
    try {
      uni.getPushClientId({
        success: (res: { cid?: string }) => {
          const cid = res?.cid;
          if (!cid) {
            resolve(null);
            return;
          }
          resolve({
            platform: uni.getSystemInfoSync().platform === "ios" ? "APP_IOS" : "APP_ANDROID",
            clientId: cid,
          });
        },
        fail: () => resolve(null),
      });
    } catch {
      // uni-push 未配置（本地跑未开推送的自定义基座）时 getPushClientId 直接抛
      resolve(null);
    }
  });
  // #endif

  // #ifndef APP-PLUS
  return Promise.resolve(null);
  // #endif
}

/**
 * 注册推送点击的落点路由。App 启动时调一次。
 *
 * <p>后端在 payload 里放 `{"link":"/pages/..."}`（见 GetuiPushGateway）——
 * **点击必须能落到那一页**：一条「新订单」推送点开却停在首页，
 * 和没推没有区别，商家还得自己去翻订单列表。
 *
 * @param navigate 由各端注入（两端的路由栈与 tab 页判定不同）
 */
export function initPush(navigate: (link: string) => void): void {
  // #ifdef APP-PLUS
  try {
    uni.onPushMessage((res: { type?: string; data?: unknown }) => {
      // type=click 才是用户点了通知；receive 是应用在前台收到，不该抢走当前页面
      if (res?.type !== "click") return;
      const data = res.data as { payload?: unknown } | undefined;
      const payload = data?.payload;
      const raw = typeof payload === "string" ? safeParse(payload) : payload;
      const link = (raw as { link?: string } | null)?.link;
      if (link) navigate(link);
    });
  } catch {
    // 同 getPushDevice：未配置推送的基座下静默降级
  }
  // #endif
}

function safeParse(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}
