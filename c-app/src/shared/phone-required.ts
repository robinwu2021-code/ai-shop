import { ref } from "vue";
import { useUserStore } from "@/stores/user";

/**
 * 「这一步要手机号」的**统一闸**。
 *
 * <p>此前这个判断散在各页，写法还不一样：有的判 `isLogin`、有的判 `user.phone`、
 * 有的跳登录页、有的弹 phone-gate。同一件事七种行为，加一个入口就要重想一遍，
 * 而漏掉的那个入口是**静默**的 —— 用户点下去没反应，或者走到最后一步才被要号。
 *
 * <h2>为什么闸的判据是「有没有手机号」，不是「登录没登录」</h2>
 *
 * <p>C 端其实有两层身份：**账号(openid)** 打开小程序就静默拿到了（`wx.login`，
 * 无感、也弹不出框）；**手机号**才需要用户授权。所以「登录」这个词在 C 端
 * 指的一直是第二层，真实门槛只有手机号这一道。
 *
 * <h2>微信的硬规矩</h2>
 *
 * <p>手机号授权**必须由用户点按钮触发**，代码里主动调是弹不出来的。
 * 所以这个闸能做到的是「立刻把带授权按钮的弹层摆到他面前」，
 * 而不是替他点。弹层里一键授权失败还有验证码兜底（见 phone-gate）。
 */

/** 弹层开关。各页把它绑到 `<phone-gate :visible="phoneRequired.visible">` */
const visible = ref(false);
/** 绑定成功后要继续干的事 —— 闸拦下来的那个动作 */
let pending: (() => void | Promise<void>) | null = null;
/** 带进弹层的预填号码（地址上的收货电话之类），省得同一个号输两遍 */
const suggest = ref("");

/**
 * 需要手机号的动作走这里。
 *
 * - 已有手机号：直接执行 `action`，不打扰
 * - 没有：弹授权层，**绑定成功后自动把 `action` 补上**（用户不用再点一次）
 *
 * @param action 真正要做的事（加购、下单、收藏、参团…）
 * @param prefill 可预填的号码，可空
 * @return 是否已经执行了 action（false = 被拦下、等绑定）
 */
export async function withPhone(
  action: () => void | Promise<void>,
  prefill?: string,
): Promise<boolean> {
  const user = useUserStore();
  // 账号这一层是静默的，极少数情况下还没拿到 —— 先补一次，别让它变成「没手机号」
  if (!user.isLogin) {
    await user.silentLogin().catch(() => false);
  }
  if (user.user?.phone) {
    await action();
    return true;
  }
  pending = action;
  suggest.value = prefill ?? "";
  visible.value = true;
  return false;
}

/** 进页即弹（分类页、店铺页用）：没号就摆出授权层，有号什么也不做 */
export async function requirePhoneOnEnter(): Promise<void> {
  await withPhone(() => {});
}

/** 弹层绑定成功 → 把拦下来的那个动作补上 */
export async function onPhoneBound(): Promise<void> {
  visible.value = false;
  const run = pending;
  pending = null;
  if (run) await run();
}

/** 用户关掉弹层 → 丢掉待办，不要在下次绑定时冒出来 */
export function onPhoneGateClose(): void {
  visible.value = false;
  pending = null;
}

export const phoneRequired = { visible, suggest };
