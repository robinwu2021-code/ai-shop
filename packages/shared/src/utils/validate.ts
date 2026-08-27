// 输入校验：**判据一处定义**。
//
// 收编前的样子：全仓 134 个 `<input>`（B 端 110 / 26 页，C 端 24 / 11 页），
// 而成型的校验只有 **7 个页面**各写一份，其余靠 `type` 与 `maxlength` ——
// 而那两样的覆盖率是 B 端 67/15、C 端 11/7。**输入这一侧没有共同层。**
//
// `format.ts` 有 money / weight / distance / maskPhone，**全是输出格式化**。
// 输入侧一直是空的，所以每张表单自己判、判到什么算什么。
//
// ⚠️ **手机号那条尤其值得记**：`/^\d{11}$/` 在 address / apply / login / mock
// 各写一份 —— 它只查长度不查号段，`00000000000` 一路畅通。
// 而正确的 `/^1[3-9]\d{9}$/` **在这个仓库里一次都没出现过**。
// 四份一样的错，比一份错更难发现：改对一处，另外三处还在放行。

/** 中国大陆手机号。**含号段** —— 只查 11 位数字会放行 `00000000000` */
const RE_PHONE = /^1[3-9]\d{9}$/;
/** 6 位数字验证码 */
const RE_OTP = /^\d{6}$/;
/** 邮箱。**故意宽松**：严格的 RFC 正则会拒掉合法地址，而这里的目的是挡手滑，
 *  真正的判据是那封信收不收得到 —— 那件事只有服务端知道 */
const RE_EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function isPhone(v: string | undefined | null): boolean {
  return RE_PHONE.test((v ?? "").trim());
}

export function isOtp(v: string | undefined | null): boolean {
  return RE_OTP.test((v ?? "").trim());
}

export function isEmail(v: string | undefined | null): boolean {
  return RE_EMAIL.test((v ?? "").trim());
}

/** 非空（去掉首尾空白之后）。`"   "` 不算填了 */
export function notBlank(v: string | undefined | null): boolean {
  return (v ?? "").trim().length > 0;
}

/**
 * 正整数（数量、库存、张数）。空串、小数、负数、`1e3` 都不算。
 *
 * <p>不接受小数是有意的：件数写 `1.5` 在业务上没有意义，
 * 而 `parseInt` 会把它悄悄变成 1 —— 用户看到的是自己填的 1.5，存下去的是 1。
 */
export function isPositiveInt(v: string | number | undefined | null): boolean {
  const s = String(v ?? "").trim();
  return /^[1-9]\d*$/.test(s);
}

/** 非负整数（库存可以是 0） */
export function isNonNegativeInt(v: string | number | undefined | null): boolean {
  return /^(0|[1-9]\d*)$/.test(String(v ?? "").trim());
}

/**
 * 金额（元）。最多两位小数，不接受负数。
 *
 * <p>**不在这里转成分** —— 那是 `toMinor` 的事（见 money.ts）。
 * 校验与换算分开：合在一起的话，「格式对不对」与「值是多少」会用同一个返回值表达，
 * 而 0 既是合法金额也是失败的默认值。
 */
export function isMoney(v: string | number | undefined | null): boolean {
  return /^(0|[1-9]\d*)(\.\d{1,2})?$/.test(String(v ?? "").trim());
}

/**
 * 只留数字。给 `@input` 用：粘贴进来的手机号常带空格或 `-`。
 *
 * <p>**在输入时就清掉，而不是提交时报错** —— 后者要用户自己找出哪里不对，
 * 而他看到的那串数字在他眼里本来就是对的。
 */
export function digitsOnly(v: string): string {
  return v.replace(/\D/g, "");
}
