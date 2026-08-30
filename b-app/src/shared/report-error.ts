/**
 * 全局错误上报。**这个 App 此前一个错误处理器都没有** ——
 * 没有 `app.config.errorHandler`、没有 uni 的 `onError`、也没有
 * `onUnhandledRejection`，于是任何 JS 异常都是静默的。
 *
 * 代价在 2026-08-29 那晚兑现：真机上工作台**整页空白且不自愈**
 * （进货 → 添加商品 → 返回之后，内容区全没，切 tab 不恢复，只有重启能救），
 * 而 `logcat` 里一条相关记录都没有 —— release 包不把 console 送进日志。
 * **查不出根因，是因为 App 拒绝报告。**
 *
 * 所以这里做两件事，两件都不是「调试开关」：
 *
 * 1. **让用户看见**。一屏空白什么也不说，比一句「出错了」坏得多：
 *    前者让人以为「这家店没有数据」，后者至少让人知道该重试或找人。
 * 2. **留下可读的痕迹**。最近 N 条存进 uni 存储，
 *    `我的 → 关于` 那类页面可以取出来看；真机上也能截图带走。
 *
 * **不上报到服务端**：那要先定采样、脱敏与留存，且商家端的错误里
 * 很可能带门店名、手机号。这一步单独做，别顺手塞进来。
 */
import { reactive } from "vue";
import { i18n } from "../i18n";

let lastToastAt = 0;
let lastMessage = "";
let lastMessageAt = 0;

/**
 * **只掐提示、不掐记录**的一批。
 *
 * 目前一条：`setNavigationBarColor:fail page not found` —— uni 运行时自己在冷启动时
 * 发的一个未捕获拒绝（应用代码里没有任何一处调这个 API，全仓搜过）。
 * 2026-08-30 实测：H5 打开工作台首页**每次必现一条**。
 *
 * 于是刚接上错误上报的那天，商家一开 App 就会看到「出错了，请重试」——
 * 而没有任何东西真的坏了。**假警报比不报警更坏**：它教会人忽略这句话，
 * 等到工作台真的整页空白那天，那句提示已经没人看了。
 *
 * 但**痕迹仍然要留**：它进存储、进 console，只是不弹。
 * 不留的话，将来这条噪声背后真的藏了别的问题，就再也查不到了。
 */
const SILENT_MESSAGES = [/setNavigationBar\w+:fail page not found/];

const KEY = "shbm_last_errors";
const MAX = 20;

export interface ReportedError {
  /** 出错的地方：`vue` / `promise` / `uni` / 调用方自己给的名字 */
  where: string;
  message: string;
  /** 毫秒时间戳。**存数字不存格式化字符串** —— 时区与语言在读的时候才定 */
  at: number;
}

/** 最近一条。界面据此显示提示条；`null` 表示当前没有未读的错误 */
export const errorState = reactive<{ last: ReportedError | null }>({ last: null });

function readAll(): ReportedError[] {
  try {
    const raw = uni.getStorageSync(KEY);
    return Array.isArray(raw) ? (raw as ReportedError[]) : [];
  } catch {
    return [];
  }
}

/** 取最近的几条，新的在前。给「关于」这类页面读 */
export function recentErrors(): ReportedError[] {
  return readAll().slice().reverse();
}

/**
 * 记一条错误。
 *
 * **自己不许再抛**：上报路径抛异常会把原始错误盖掉，
 * 而那时候人手里只剩一个来自上报代码的堆栈，指向完全错误的方向。
 */
export function reportError(err: unknown, where = "unknown"): void {
  try {
    /*
     * **同一个错误会从两个入口进来。** 2026-08-30 在 H5 上实测：
     * 一次 Vue 渲染错误落了两条 —— `vue:render` 一条、`uni` 一条，
     * uni 把 Vue 的错误又转给了自己的 `onError`。
     * 不去重的话，20 条的环形缓冲会被同一件事填满，而真正在前面的那条被挤掉。
     */
    const message =
      err instanceof Error
        ? `${err.name}: ${err.message}`
        : typeof err === "string"
          ? err
          : JSON.stringify(err);
    const msg = String(message).slice(0, 500);
    const now0 = Date.now();
    // 1 秒内同一条消息只记一次（`where` 不参与比较 —— 转发过来的那条 where 不同）
    if (msg === lastMessage && now0 - lastMessageAt < 1000) return;
    lastMessage = msg;
    lastMessageAt = now0;

    const rec: ReportedError = { where, message: msg, at: now0 };
    errorState.last = rec;

    const all = readAll();
    all.push(rec);
    // 只留最后 MAX 条：存储没有上限保护的话，一个循环里抛的错能把它撑爆
    uni.setStorageSync(KEY, all.slice(-MAX));

    // H5 / 调试包里这一行是最快的线索；release 包不进 logcat，所以上面那两步才是主路
    console.error(`[${where}]`, err);

    /*
     * **给用户一句话。** 节流 5 秒：一个渲染错误往往连抛好几次，
     * 每次弹一个 toast 会把界面压死，而人拿到的信息并不会更多。
     *
     * 只说「出错了，可以重试」不说堆栈：堆栈对商家没有意义，
     * 而它可能带门店名。要看细节的走 recentErrors()。
     */
    const now = Date.now();
    const silent = SILENT_MESSAGES.some((re) => re.test(msg));
    if (!silent && now - lastToastAt > 5000) {
      lastToastAt = now;
      /*
       * `i18n.global.t` 的类型是 legacy 与 composition 两种签名的联合，
       * 直接调用 tsc 不认（TS2349）。组件里用的是 `useI18n()`，而这里在组件外，
       * 只能走 global —— 窄化成「收一个 key 返回 string」，这正是此处用到的那一种。
       */
      const t = (i18n.global as unknown as { t: (key: string) => string }).t;
      uni.showToast({ title: t("common.unexpected"), icon: "none", duration: 2000 });
    }
  } catch {
    /* 上报失败就算了 —— 见函数注释 */
  }
}

/** 把三个入口都接上。`main.ts` 在 `createApp` 里调一次 */
export function installErrorReporting(app: { config: { errorHandler?: unknown } }): void {
  app.config.errorHandler = (err: unknown, _vm: unknown, info: string) => {
    reportError(err, `vue:${info || "render"}`);
  };
  // uni 的两个全局钩子：Vue 的 errorHandler 接不到它们
  //（前者是原生层与页面生命周期抛出的，后者是没人 catch 的 Promise）
  if (typeof uni?.onError === "function") uni.onError((e: unknown) => reportError(e, "uni"));
  if (typeof uni?.onUnhandledRejection === "function") {
    uni.onUnhandledRejection((e: { reason?: unknown }) => reportError(e?.reason ?? e, "promise"));
  }
  /*
   * **H5 上 `uni.onUnhandledRejection` 存在但不干活**（2026-08-30 实测：
   * 钩子 `typeof` 是 function，注册后抛一个未捕获的拒绝，它一次都不进来）。
   * 所以再接一层 DOM 事件 —— App 上没有 `window` 这个分支自然跳过，
   * 而去重那一段保证了两边都进来时也只记一条。
   *
   * 这条不是为了 H5 好看：mock H5 是这个仓库验交互的既定路子，
   * 而在那条路上看不见的错误，等于没有人会在上线前发现它。
   */
  if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
    window.addEventListener("unhandledrejection", (e: PromiseRejectionEvent) => {
      reportError(e?.reason ?? e, "promise");
    });
  }
}
