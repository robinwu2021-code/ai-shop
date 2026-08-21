// 端能力：登录与注册。
//
// 各端可用的方式不同：
//   小程序：微信静默登录（拿 openid）· 手机号 OTP（兜底）
//   App   ：手机号 OTP · 微信开放平台 · Apple（iOS 上架硬要求：接了第三方登录就必须提供 Apple）
//   H5    ：手机号 OTP
//
// **小程序为什么是静默登录而不是「一键取手机号」**（TDD-小程序登录打通 §3.1）：
// 后者要 `<button open-type="getPhoneNumber">` 的 encryptedData 由服务端解密，
// 而那个接口很可能要求微信认证 —— 一期不认证。静默登录不需要任何资质，
// 且它拿到的 openid 正是**微信支付 JSAPI 下单的必填参数**，先拿到就不用回头改登录。
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

/**
 * 手机号 + 密码。**只在 B 端出现**（见 {@link loginMethods}）。
 *
 * <p>与 {@link phoneOtp} 共用「手机号 + 一个副凭证」的表单形态，所以 `needsPhone` 同样是
 * true —— 页面那一格填的是密码而不是验证码，由页面按当前方式换输入框与文案。
 *
 * <p><b>它不建户</b>：后端对这条路查无此人也报「手机号或密码不对」，不会像其它方式
 * 那样「登录即注册」。所以它不能是 `primary` —— 新商家第一次来必须走验证码那条。
 */
const password: LoginMethod = {
  id: "PASSWORD",
  labelKey: "login.byPassword",
  primary: false,
  needsPhone: true,
  async acquire(phone, pwd) {
    if (!phone) throw new Error("请输入手机号");
    return { grantType: "PASSWORD", principal: phone, credential: pwd };
  },
};

/**
 * 小程序静默登录：`wx.login` 的 code 交给服务端换 openid/unionid。
 *
 * 拿不到手机号 —— 这是它与 {@link wxPhone} 的全部差别。需要手机号的场景
 * （下单联系人、商家账号主标识）走绑定流程另说，不是登录这一步的事。
 */
const wxMini: LoginMethod = {
  id: "WX_MINI",
  labelKey: "login.byWxMini",
  primary: true,
  needsPhone: false,
  async acquire() {
    return { grantType: "WX_MINI", principal: await wxLoginCode() };
  },
};

/*
 * 这里曾有一个 `wxPhone`（`WX_PHONE`，小程序一键取手机号）作为小程序首选。
 * 它**从来没有真正工作过**：`acquire()` 只取了 login code，而一键取手机号要的是
 * `<button open-type="getPhoneNumber">` 的 encryptedData 由服务端解密；
 * 后端也没有 `WX_PHONE` 分支，请求进去直接 400。
 *
 * 不改成「补齐 WX_PHONE」而是换成静默登录，是因为该接口的前置是微信认证，
 * 而一期不认证。`GrantType` 里保留 `WX_PHONE` 取值不动 —— 它是将来的路，
 * 挂账记在 tests/enum-alignment.test.ts。
 */

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
 *
 * @param opts.withPassword 是否提供密码登录。**这一项是 app 的选择，不是端的能力** ——
 *   密码只有 B 端有（商家高频开合），C 端不传就没有。所以它做成参数而不是
 *   `#ifdef`：条件编译分的是运行平台，而这里分的是哪一个 app。
 */
export function loginMethods(opts?: { withPassword?: boolean }): LoginMethod[] {
  const pwd = opts?.withPassword ? [password] : [];

  // #ifdef MP-WEIXIN
  return [wxMini, phoneOtp, ...pwd];
  // #endif

  // #ifdef APP-PLUS
  // eslint-disable-next-line no-unreachable
  const list: LoginMethod[] = [phoneOtp, ...pwd, wxOpen];
  if (uni.getSystemInfoSync().platform === "ios") list.push(apple);
  return list;
  // #endif

  // #ifndef MP-WEIXIN || APP-PLUS
  // eslint-disable-next-line no-unreachable
  return [phoneOtp, ...pwd];
  // #endif
}

/*
 * 这里曾有一个 `acquireCredential(phone, otp)`「取首选方式」的兼容壳。
 * 它是 c-app 登录页此前唯一的入口，而**那个页面写死渲染手机号表单**——
 * 于是小程序上取到的首选是微信登录，用户填的手机号与验证码被静默丢弃，
 * 界面显示的与实际发出的不是一回事。
 *
 * 删掉它是为了让这种错配不可能再发生：页面只能通过 `loginMethods()` 拿方式，
 * 拿到什么就得渲染什么。
 */
