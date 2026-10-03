// 消息中心 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, persist } from "@shared/mock/db";
import type { MerchantApi } from "../contract";

export const messageMock: Pick<MerchantApi,
  "mMessageList"
  | "mMessageUnread"
  | "mMessageRead"
  | "mMessageReadAll"
  | "mRegisterPushToken"
  | "mUnregisterPushToken"
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

  // mock 世界没有真设备（H5 下 getPushDevice 恒为 null，这两个不会被调到）
  async mRegisterPushToken() {
    return delay(undefined);
  },

  async mUnregisterPushToken() {
    return delay(undefined);
  },
};
