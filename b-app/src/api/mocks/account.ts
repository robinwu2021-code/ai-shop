// 账号与入驻：登录、主体资料、证照、进件 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, nextNo, persist } from "@shared/mock/db";
import { isPhone } from "@shared/utils/validate";
import {
  mockState,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const accountMock: Pick<MerchantApi,
  "mLogin"
  | "mSendOtp"
  | "mSetPassword"
  | "mHasPassword"
  | "mStaffLogin"
  | "mProfile"
  | "mApply"
  | "mQuickStart"
  | "mApplyDraft"
> = {
  // ---------------------------------------------------------------- 账号与入驻
  async mLogin(req) {
    // 注册的合规前置：没勾协议不建号。真实后端要把同意时间与协议版本号一起留痕
    if (!req.agreed) throw new Error("请先阅读并同意协议");

    // 手机号是商家账号的主标识；第三方登录拿到的是 code，手机号由服务端换取后回填。
    // mock 无服务端换号能力，这里用占位号让流程能继续，并在 profile 上标出待补绑。
    const isPhone = req.grantType === "PHONE_OTP";
    if (isPhone && !/^\d{11}$/.test(req.principal)) throw new Error("手机号格式不对");
    db.merchant.phone = isPhone ? req.principal : db.merchant.phone || "";
    db.merchant.loginBy = req.grantType;
    persist();
    return delay({ token: `mock-b-token-${Date.now()}`, merchant: { ...db.merchant } });
  },

  async mSendOtp(phone: string) {
    if (!/^\d{11}$/.test(phone)) throw new Error("手机号格式不对");
    await delay(undefined);
  },

  /** mock 里密码只存在内存：它只为让「设了密码 → 能用密码登录」这条链在 mock 下走得通 */
  async mSetPassword(password: string) {
    if (password.length < 6) throw new Error("密码至少 6 位");
    mockState.password = password;
    await delay(undefined);
  },

  async mHasPassword() {
    await delay(undefined);
    return { hasPassword: mockState.password.length > 0 };
  },

  async mStaffLogin(payload) {
    /*
     * mock 也照「非在职员工返回 403」来：恒成功的话，
     * 「输错号码时该显示什么」这段永远走不到，而它是员工登录最常见的一次失败。
     */
    const staff = db.staff.find(
      (x) => x.status === "ACTIVE" && x.loginPhone === payload.phone,
    );
    if (!staff) throw new Error("该手机号不是本店员工");
    return delay({ token: "demo-staff-token", merchant: { ...db.merchant } });
  },

  async mProfile() {
    return delay({ ...db.merchant });
  },

  async mApply(payload) {
    // 一份记录同时承载内容与进度 —— 后端 usr_merchant_apply 就是一行
    db.merchantApply = {
      ...payload,
      applyNo: db.merchantApply?.applyNo || nextNo("MA"),
      status: "PENDING",
      createdAt: Date.now(),
    };
    db.merchant = {
      ...db.merchant,
      // 提交后是 APPLYING（已交，等着）而不是 REVIEWING（有人在看）——
      // 此刻还没有任何人受理，报 REVIEWING 是替运营做了一个没发生的承诺
      merchantNo: db.merchant.merchantNo || nextNo("M"),
      name: payload.name,
      subject: payload.subject,
      status: "APPLYING",
      /*
       * **重提要把上一次的拒因清掉。**
       *
       * 真后端不会有这个残留：那边 rejectReason 取自**当前那张申请单**，
       * 而重提是新建一张单（OpsServiceImpl#createApply），新单天然没有拒因。
       * mock 只有一个 merchant 对象，不显式清就会一直带着上次的话 ——
       * 页面此刻看不出来（驳回卡的判据是 status === 'REJECTED'），
       * 但任何按「rejectReason 非空 = 被拒过」判断的地方都会误判。
       */
      rejectReason: undefined,
    };
    persist();
    return delay({ ...db.merchant });
  },

  async mQuickStart(payload) {
    /*
     * 无证照快速开店。与 mApply 的差别就是这个 status —— **不进审核队列**，
     * 当场就能干活，只是买家看不到（真实后端的可见性闸门按主体状态挡，
     * mock 这边没有 C 端可见性可模拟，所以只体现在状态上）。
     */
    db.merchant = {
      ...db.merchant,
      merchantNo: db.merchant.merchantNo || nextNo("M"),
      name: payload.storeName,
      status: "PENDING_LICENSE",
    };
    persist();
    return delay({ ...db.merchant });
  },

  async mApplyDraft() {
    return delay(db.merchantApply ? { ...db.merchantApply } : null);
  },
};
