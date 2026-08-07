// 端能力：登录与注册。
//
// 各端可用的方式不同：
//   小程序：微信一键取手机号（最快，一次授权直接拿到号）· 手机号 OTP（兜底）
//   App   ：手机号 OTP · 微信开放平台 · Apple（iOS 上架硬要求：接了第三方登录就必须提供 Apple）
//   H5    ：手机号 OTP
//
// **页面不写 `#ifdef`**（规范约束）：页面只问 `loginMethods()` 当前端能用哪几种，
// 拿到的每一项都带同签名的 `acquire()`，业务层照调即可。
import type { GrantType } from "@shared/types";

export interface Credential {
  grantType: GrantType;
  principal: string;
  credential?: string;
}

export interface LoginMethod {
  id: GrantType;
  /** i18n key，文案由各端自己的词条提供 */
  labelKey: string;
  /** 该端的推荐方式：排首位、用主按钮样式 */
  primary: boolean;
  /** 需要页面先收集手机号与验证码 */
  needsPhone: boolean;
  acquire(phone?: string, otp?: string): Promise<Credential>;
}

/** 小程序：wx.login 拿 code（换 openid/unionid 在服务端做） */
export function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      success: (res) => resolve(res.code || ""),
      fail: (e) => reject(new Error(e.errMsg || "微信登录失败")),
    });
  });
}

function providerLogin(provider: "weixin" | "apple"): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider,
      success: (res) => resolve(res.code || ""),
      fail: (e) => reject(new Error(e.errMsg || "第三方登录失败")),
    });
  });
}

const phoneOtp: LoginMethod = {
  id: "PHONE_OTP",
  labelKey: "login.byPhone",
  primary: true,
  needsPhone: true,
  async acquire(phone, otp) {
    if (!phone) throw new Error("请输入手机号");
    return { grantType: "PHONE_OTP", principal: phone, credential: otp };
  },
};

const wxPhone: LoginMethod = {
  id: "WX_PHONE",
  labelKey: "login.byWxPhone",
  primary: true,
  needsPhone: false,
  async acquire() {
    // 真实实现：<button open-type="getPhoneNumber"> 拿 encryptedData，服务端解密出手机号。
    // 这里取 login code，服务端凭它 + 前端回传的加密串完成绑定。
    return { grantType: "WX_PHONE", principal: await wxLoginCode() };
  },
};

const wxOpen: LoginMethod = {
  id: "WX_OPEN",
  labelKey: "login.byWechat",
  primary: false,
  needsPhone: false,
  async acquire() {
    return { grantType: "WX_OPEN", principal: await providerLogin("weixin") };
  },
};

const apple: LoginMethod = {
  id: "APPLE",
  labelKey: "login.byApple",
  primary: false,
  needsPhone: false,
  async acquire() {
    return { grantType: "APPLE", principal: await providerLogin("apple") };
  },
};

/**
 * 当前端可用的登录方式，按推荐顺序。
 *
 * ⚠️ Apple 只在 iOS 出现 —— 安卓包里放一个 Apple 登录按钮是审核与体验双输。
 */
export function loginMethods(): LoginMethod[] {
  // #ifdef MP-WEIXIN
  return [wxPhone, phoneOtp];
  // #endif

  // #ifdef APP-PLUS
  // eslint-disable-next-line no-unreachable
  const list: LoginMethod[] = [phoneOtp, wxOpen];
  if (uni.getSystemInfoSync().platform === "ios") list.push(apple);
  return list;
  // #endif

  // #ifndef MP-WEIXIN || APP-PLUS
  // eslint-disable-next-line no-unreachable
  return [phoneOtp];
  // #endif
}

/** 兼容旧调用：取当前端的首选方式 */
export async function acquireCredential(phone?: string, otp?: string): Promise<Credential> {
  const [first] = loginMethods();
  if (!first) throw new Error("当前端没有可用的登录方式");
  return first.acquire(phone, otp);
}
