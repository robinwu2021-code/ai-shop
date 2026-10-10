// 用户：积分、账号、地址簿 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, db, delay, nextNo, persist } from "@shared/mock/db";
import { POINTS } from "@shared/utils/constants";
import { isPhone } from "@shared/utils/validate";
import {
  buildAccount,
} from "./_shared";
import type { ShopApi } from "../contract";

export const userMock: Pick<ShopApi,
  "pointAccount"
  | "pointRecords"
  | "pointsDeductible"
  | "sendOtp"
  | "login"
  | "profile"
  | "updateProfile"
  | "uploadAvatar"
  | "setPassword"
  | "passwordState"
  | "logout"
  | "deregister"
  | "bindPhone"
  | "bindPhoneByWx"
  | "phoneCapable"
  | "bindCommunity"
  | "activeAddress"
  | "switchActiveAddress"
  | "addressList"
  | "saveAddress"
  | "removeAddress"
  | "setDefaultAddress"
> = {
  // ---------------------------------------------------------------- 积分
  async pointAccount() {
    return delay(buildAccount(db.points));
  },

  async pointRecords() {
    return delay([...db.points]);
  },

  async pointsDeductible(q) {
    // 与服务端同一套判据：四级开关 → 上限 → 余额，三者取小
    const acc = buildAccount(db.points);
    const cap = Math.floor(q.payableMinor * POINTS.maxDeductRatio) * POINTS.perMinor;
    const maxPoints = Math.min(acc.balance, cap);
    return delay({
      maxPoints,
      maxAmountMinor: Math.floor(maxPoints / POINTS.perMinor),
      balance: acc.balance,
    });
  },

  // ---------------------------------------------------------------- 用户
  async sendOtp(phone: string) {
    // mock 不真的发短信；验证码固定 1234（登录页也照这条口径提示）
    if (!/^\d{11}$/.test(phone)) throw new Error("手机号格式不对");
    await delay(undefined);
  },

  async login(req) {
    // 进店归因：从店铺码/店铺分享进来时带 merchantNo，写入「常去的店」。
    // 它同时决定订单的 trafficSource 与费率档（ADR-004 §5.4 / §6）
    if (req.merchantNo) db.user.merchantNo = req.merchantNo;
    return delay({ token: `mock-token-${Date.now()}`, user: { ...db.user } });
  },

  async profile() {
    return delay({ ...db.user });
  },

  // ---------------------------------------------------------------- 个人资料（C-AC-08）

  async updateProfile(req) {
    /*
     * 判据与后端同口径：**传 undefined 不动，传空白则拒**。
     *
     * 替身在这里宽容一点就会盖住真缺陷 —— 后端的空白此前是静默忽略
     * （200 + 「已保存」而名字没变），如果 mock 也静默忽略，
     * 端上那条「保存后名字要真的变了」的用例在 mock 下永远绿。
     */
    if (req.nickname !== undefined) {
      const trimmed = req.nickname.trim();
      if (!trimmed || trimmed.length > 20) throw new Error("昵称要 1–20 个字");
      db.user.nickname = trimmed;
      db.user.nicknameSet = true;
    }
    if (req.avatar !== undefined) db.user.avatar = req.avatar;
    persist();
    return delay({ ...db.user });
  },

  async uploadAvatar(tempPath) {
    // 替身存不了字节，直接把临时路径当成头像地址 ——
    // 真实环境里后端回的是公开 URL，形状一致（都是个能喂给 <image> 的串）
    db.user.avatar = tempPath;
    persist();
    return delay({ ...db.user });
  },

  async setPassword(password) {
    // 先判「能不能设」再判密码本身：顺序与后端一致。
    // 反过来的话，没绑手机号的人会先看到「密码太短」这种无关的话
    if (!db.user.phone) throw new Error("先绑定手机号才能设置登录密码");
    if (!password || password.length < 6) throw new Error("密码至少 6 位");
    db.userHasPassword = true;
    persist();
    return delay(undefined as void);
  },

  async passwordState() {
    return delay({
      hasPassword: Boolean(db.userHasPassword),
      // canSet 等价于「绑了手机号没有」—— 与后端查 PHONE 凭证同一个含义
      canSet: Boolean(db.user.phone),
    });
  },

  /** mock 下没有服务端会话可作废，直接放行。真实环境由后端 revoke 令牌 */
  async logout() {
    return delay(undefined as void);
  },

  async deregister() {
    db.user.phone = "";
    db.user.nickname = "已注销用户";
    return delay(undefined as unknown as void);
  },

  async bindPhone(phone: string) {
    db.user.phone = phone;
    return delay({ ...db.user });
  },

  async bindPhoneByWx() {
    // mock 侧一键授权恒可用，且给一个固定号 —— 真后端桩通道是**返回 null 并报 70027**
    db.user.phone = "13800138000";
    return delay({ ...db.user });
  },

  async phoneCapable() {
    return delay({ capable: true });
  },

  async bindCommunity(communityNo, pickupNo) {
    const seed = allCommunitySeeds().find((c) => c.communityNo === communityNo);
    if (!seed) throw new Error("社区不存在");
    const pk = seed.pickups.find((p) => p.pickupNo === pickupNo);
    if (!pk) throw new Error("自提点不存在");
    db.user.communityNo = communityNo;
    db.user.pickupNo = pickupNo;
    // 自提点由入驻商家承接（ADR-005）：绑点的同时把承接商家记为「常去的店」
    db.user.merchantNo = pk.hostMerchantNo;
    return delay({ ...db.user });
  },

  // ------------------------------------------------------- 当前生效位置
  /**
   * **替身要和正主一样会返回空。** 新用户没有位置是常态，
   * 而 mock 里若总是给一条，「没有位置时首页什么样」这条路就永远测不到 ——
   * 那正是最容易做坏的一条（空白页等他去选）。
   */
  async activeAddress() {
    const id = db.user.activeAddressId;
    return delay(id ? (db.addresses.find((a) => a.addressId === id) ?? null) : null);
  },

  async switchActiveAddress(addressId) {
    const a = db.addresses.find((x) => x.addressId === addressId);
    if (!a) throw new Error("地址不存在");
    db.user.activeAddressId = addressId;
    // **刻意不碰 isDefault** —— 与真后端同一条规则，替身松一点就等于没测
    persist();
    return delay({ ...a });
  },

  // ---------------------------------------------------------------- 地址簿
  async addressList() {
    return delay([...db.addresses]);
  },

  async saveAddress(payload) {
    // 与真后端同一条判据（`Phones.CN_MOBILE`）。**替身不能比正主松** ——
    // 收货人电话是履约那一端唯一的联系方式，mock 放行等于把这条链路的验收让过去了
    if (!isPhone(payload.phone ?? "")) throw new Error("手机号格式不对，应为 11 位大陆手机号");
    if (payload.addressId) {
      const i = db.addresses.findIndex((a) => a.addressId === payload.addressId);
      if (i < 0) throw new Error("地址不存在");
      db.addresses[i] = { ...db.addresses[i]!, ...payload, addressId: payload.addressId };
    } else {
      db.addresses.push({ ...payload, addressId: nextNo("AD") });
    }
    // 设了默认就把别的取消 —— 默认地址只能有一个
    if (payload.isDefault) {
      const target = payload.addressId ?? db.addresses[db.addresses.length - 1]!.addressId;
      db.addresses.forEach((a) => (a.isDefault = a.addressId === target));
    }
    // 第一条地址自动成为默认，省得用户还要再点一次
    if (db.addresses.length === 1) db.addresses[0]!.isDefault = true;
    persist();
    return delay([...db.addresses]);
  },

  async removeAddress(addressId) {
    const wasDefault = db.addresses.find((a) => a.addressId === addressId)?.isDefault;
    db.addresses = db.addresses.filter((a) => a.addressId !== addressId);
    // 删掉的是默认地址就把第一条顶上，避免出现「一条都不是默认」的状态
    if (wasDefault && db.addresses[0]) db.addresses[0].isDefault = true;
    persist();
    return delay([...db.addresses]);
  },

  async setDefaultAddress(addressId) {
    db.addresses.forEach((a) => (a.isDefault = a.addressId === addressId));
    persist();
    return delay([...db.addresses]);
  },
};
