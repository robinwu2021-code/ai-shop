// 消息中心 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, persist } from "@shared/mock/db";
import { currentStoreNo } from "./_shared";
import type { MerchantApi } from "../contract";

export const messageMock: Pick<MerchantApi,
  "mMessageList"
  | "mMessageUnread"
  | "mMessageRead"
  | "mMessageReadAll"
  | "mSubscribeReport"
  | "mRegisterPushToken"
  | "mUnregisterPushToken"
  | "mNotifySetting"
  | "mSaveNotifySwitch"
  | "mSaveNotifyWecom"
  | "mTestNotifyWecom"
  | "mSaveNotifyPhones"
  | "mSaveNotifyEmail"
> = {
  // ---- 消息。mock 世界与 C 端共用一个消息池（没有 receiver 维度）——
  // 这里演示的是消息中心的交互，不是收件箱隔离；隔离由后端场景测试保证
  async mMessageList() {
    return delay([...db.messages].sort((a, b) => b.at - a.at));
  },

  async mMessageUnread() {
    return delay(db.messages.filter((m) => !m.read).length);
  },

  async mMessageRead(messageNo) {
    const m = db.messages.find((x) => x.messageNo === messageNo);
    if (m) m.read = true;
    persist();
    return delay([...db.messages]);
  },

  async mMessageReadAll() {
    db.messages.forEach((m) => (m.read = true));
    persist();
    return delay([...db.messages]);
  },

  // mock 世界不在小程序里，弹不出授权框，这条不会被调到
  async mSubscribeReport() {
    return delay(undefined);
  },

  // mock 世界没有真设备（H5 下 getPushDevice 恒为 null，这两个不会被调到）
  async mRegisterPushToken() {
    return delay(undefined);
  },

  async mUnregisterPushToken() {
    return delay(undefined);
  },

  // ---- 通知设置（TDD-来单四渠道与商家通知设置）。
  //
  // **替身的开关存在内存里而不是 db**：mock 的 db 是演示数据的真源（会被 persist 到
  // storage），而通知开关是「这一次把玩」的状态 —— 存进去的话，演示数据重置时
  // 开关会被一起还原回去，而页面上看不出为什么。
  //
  // **按门店分桶**（currentStoreNo）：后端这四条都按 X-Store-No 取数，
  // 替身不认门店的话切了店这一页纹丝不动 —— 看起来就是「切店没做好」，
  // 而切店本身是好的，只是替身看不见它。单店时它返回空串，所有设置落在同一桶里。
  //
  // 四条默认全开，与后端「缺行 = 开」一致。
  async mNotifySetting() {
    return delay(notifySettingSnapshot(currentStoreNo()));
  },

  async mSaveNotifySwitch(scene, channel, enabled) {
    const at = currentStoreNo();
    const store = (notifySwitches[at] ??= {});
    (store[scene] ??= { ...DEFAULT_SWITCHES })[channel] = enabled;
    return delay(notifySettingSnapshot(at));
  },

  async mSaveNotifyWecom(webhook) {
    // 只记「配过没有」—— 替身也不留 URL，与后端「永不回显」同一个口径
    const at = currentStoreNo();
    wecomByStore[at] = webhook.trim().length > 0;
    return delay(notifySettingSnapshot(at));
  },

  async mTestNotifyWecom() {
    return delay(wecomByStore[currentStoreNo()] ?? false);
  },

  async mSaveNotifyPhones(phones) {
    const at = currentStoreNo();
    // 与后端同一条规矩：**最多两个**，超了整笔拒（替身也拒，否则页面上试不出这条）
    if (phones.length > 2) throw new Error("最多两个");
    phonesByStore[at] = [...phones];
    return delay(notifySettingSnapshot(at));
  },

  async mSaveNotifyEmail(email) {
    const at = currentStoreNo();
    emailByStore[at] = email.trim() || null;
    return delay(notifySettingSnapshot(at));
  },
};

/** 与后端 SCENES 同序：来单 / 售后申请 / 新评价 */
const NOTIFY_SCENES = ["SUB_ORDER_PAID", "AFTER_SALE_APPLIED", "REVIEW_CREATED"];

/** 键序即页面顺序，与后端 MchNotifyPref.SWITCHABLE 一致 */
const DEFAULT_SWITCHES: Record<string, boolean> = {
  WXSUB: true, WEBHOOK: true, SMS: true, MAIL: true, PUSH: true,
};

/** 门店号 → 场景 → 通道 → 开关 */
const notifySwitches: Record<string, Record<string, Record<string, boolean>>> = {};
/** 门店号 → 企微群配过没有 */
const wecomByStore: Record<string, boolean> = {};
/** 门店号 → 额外短信号 */
const phonesByStore: Record<string, string[]> = {};
/** 门店号 → 邮件地址 */
const emailByStore: Record<string, string | null> = {};

/**
 * @param at 门店号。**由调用方传进来而不是在这里取**：那道守卫扫的是接口方法体里
 *   有没有出现 `currentStoreNo()`，藏进辅助函数它就看不见 ——
 *   而它看不见这件事，与「替身真的不认门店」长得一模一样，没法只靠读代码分辨。
 */
function notifySettingSnapshot(at: string) {
  const store = notifySwitches[at] ?? {};
  return {
    scenes: NOTIFY_SCENES.map((scene) => ({
      scene,
      switches: { ...DEFAULT_SWITCHES, ...(store[scene] ?? {}) },
    })),
    extraPhones: phonesByStore[at] ?? [],
    // 替身世界里的「店主登录手机号」—— 真后端取的是 mch_account.login_phone
    ownerPhone: "13800000000",
    email: emailByStore[at] ?? null,
    wecomReady: wecomByStore[at] ?? false,
  };
}
