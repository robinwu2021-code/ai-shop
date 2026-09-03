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
  /*
   * **把没配的模板号剔掉再问。**
   *
   * 未配置时这里是 `STUB_TPL_*` 那样的占位串。微信对 tmplIds 是**整批校验**的：
   * 里面混一个不存在的号，整次调用直接 fail —— 连同批里合法的那个也拿不到授权。
   * 症状是「用户从没见过授权弹窗，后端配额恒为 0，而两端各自看都配好了」。
   *
   * 这在退款模板缺席时是必然发生的：本小程序的公共模板库里没有那一类，
   * 而支付成功页原本一次要两个。
   */
  const ids = tmplIds.filter((id) => id && !id.startsWith("STUB_"));
  if (!ids.length) return Promise.resolve({ accepted: [], rejected: [] });

  // #ifdef MP-WEIXIN
  return new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds: ids,
      success: (res) => {
        // 微信按模板逐个给结果：'accept' / 'reject' / 'ban'（被封禁的当拒绝处理）。
        // 类型声明里没有按模板名的索引签名，实际响应有 —— 以运行时形状为准
        const byTmpl = res as unknown as Record<string, string>;
        resolve({
          accepted: ids.filter((id) => byTmpl[id] === "accept"),
          rejected: ids.filter((id) => byTmpl[id] && byTmpl[id] !== "accept"),
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

/** 后端 notify_push_token.platform 的取值。**与后端 MsgPushToken 的常量逐字一致**。 */
export type PushPlatform = "APP_ANDROID" | "APP_IOS";

/**
 * 推送供应商。**与后端 PushProvider 逐字一致**。
 * uni-push 打包的底座是个推，故恒 GETUI；将来海外包直连 FCM、iOS 直连 APNs 时，
 * 那些构建各自上报 "FCM" / "APNS"（后端 PushRouter 据此分发）。
 */
export type PushProvider = "GETUI" | "FCM" | "APNS";

export interface PushDevice {
  platform: PushPlatform;
  /** 供应商。uni-push 底座即个推，恒 "GETUI"。 */
  provider: PushProvider;
  /** 供应商设备标识（个推 cid / FCM token / APNs token） */
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
  /*
   * **个推原生直连**（不走 `uni.getPushClientId`——那是 uni-push/DCloud，要实名认证开通，
   * 我们没开通，register 会 errorCode 1）。改用 `plus.android` 反射直接调个推 SDK：
   * `PushManager.initialize()` + 轮询 `getClientid()`。cid 由离线包里的
   * `top.hxmall.bapp.GetuiIntentService.onReceiveClientId` 接住，SDK 侧同步可取。
   * appid/appkey/appsecret 走 AndroidManifest 的 PUSH_* meta。
   */
  return new Promise((resolve) => {
    // 仅 Android（plus.android 存在）。iOS 走 APNs，另行接入
    const android = (globalThis as unknown as {
      plus?: {
        android?: {
          importClass: (n: string) => { getInstance: () => Record<string, (...a: unknown[]) => unknown> };
          runtimeMainActivity: () => { getApplicationContext: () => unknown };
        };
      };
    }).plus?.android;
    if (!android) {
      resolve(null);
      return;
    }
    try {
      const PushManager = android.importClass("com.igexin.sdk.PushManager");
      const pm = PushManager.getInstance();
      const ctx = android.runtimeMainActivity().getApplicationContext();
      // initialize 幂等：确保个推已起（cid 是注册成功后异步下发的）
      (pm.initialize as (c: unknown) => void)(ctx);
      let tries = 0;
      const tick = () => {
        let cid = "";
        try {
          cid = ((pm.getClientid as (c: unknown) => string)(ctx) as string) || "";
        } catch {
          cid = "";
        }
        if (cid) {
          resolve({
            platform: uni.getSystemInfoSync().platform === "ios" ? "APP_IOS" : "APP_ANDROID",
            provider: "GETUI",
            clientId: cid,
          });
          return;
        }
        // 个推注册约 2~5s 出 cid；轮询到 ~15s 拿不到就放弃（推送是加速通道，不阻塞登录）
        if (++tries >= 30) {
          resolve(null);
          return;
        }
        setTimeout(tick, 500);
      };
      tick();
    } catch {
      resolve(null);
    }
  });
  // #endif

  // #ifndef APP-PLUS
  // H5 / 小程序没有推送通道（走站内信与订阅消息）。推送只在原生 App 构建里有。
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
  /*
   * **不再调 `uni.onPushMessage`** —— 那是 uni-push/DCloud 的 API，我们已切成个推原生直连、
   * 移除了 DCloud push 模块，再调它会弹「push 没有安装」。
   *
   * 通知点击的深链路由统一走全局 `__onPushClick`：
   * - 个推原生直连（离线包）：`GetuiIntentService.onNotificationMessageClicked` 取出
   *   payload.link，用 evaluateJavascript 调 `window.__onPushClick(link)`；
   * - WebView 壳（android-shell）：原生 PushBridge 同样调它；
   * - 普通浏览器：没人调，挂着无害。
   */
  (globalThis as unknown as { __onPushClick?: (link: string) => void }).__onPushClick = (
    link: string,
  ) => {
    if (link) navigate(link);
  };

  // #ifdef APP-PLUS
  /*
   * **申请通知权限**（Android 13+ / targetSdk 33 起必须运行时申请 POST_NOTIFICATIONS）。
   *
   * 这一步以前是 DCloud push 模块替我们做的；改成个推原生直连、移除那个模块之后
   * **没人再申请它** —— 症状极具迷惑性：个推回 `successed_online`、
   * `onNotificationMessageArrived` 也进了，但系统把通知**静默丢弃**
   * （`dumpsys notification` 里 numEnqueuedByApp=3 / numPostedByApp=0，
   * appops 显示 POST_NOTIFICATION: ignore），用户一条也看不到。
   */
  const plusAndroid = (globalThis as unknown as {
    plus?: {
      android?: {
        requestPermissions?: (
          list: string[],
          success: (r: unknown) => void,
          fail: (e: unknown) => void,
        ) => void;
        importClass?: (n: string) => Record<string, (...a: unknown[]) => unknown> | undefined;
      };
    };
  }).plus?.android;
  try {
    plusAndroid?.requestPermissions?.(
      ["android.permission.POST_NOTIFICATIONS"],
      () => {},
      () => {},
    );
  } catch {
    // 低版本 Android 没有这个权限、或非 App 环境：无需申请，静默跳过
  }

  /*
   * 消费「用户点了通知」留下的深链。原生侧（GetuiIntentService.onNotificationMessageClicked）
   * 把 link 存进静态字段，这里取走 —— **点击常伴随冷启动**，那时 webview 还没建好，
   * 让原生直接回调 JS 必丢，所以改成 JS 起来后主动取。
   * onShow 也取一次：通知点击时应用可能只是从后台唤到前台。
   */
  const takePendingLink = () => {
    try {
      const svc = plusAndroid?.importClass?.("top.hxmall.bapp.GetuiIntentService");
      const link = svc?.takePendingLink?.() as string | undefined;
      if (link) navigate(link);
    } catch {
      // 非本离线包（如自定义基座）里没有这个类：忽略
    }
  };
  takePendingLink();
  uni.onAppShow?.(takePendingLink);
  // #endif
}

