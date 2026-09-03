import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

/**
 * 取一个函数的函数体。**取不到要让调用方能断言**（返回 null 而不是空串）——
 * 函数被改名之后静默返回空串的话，下面每一条断言都会「通过」，
 * 而被守的那件事一行都没被检查到。
 */
function bodyOf(src: string, signature: string): string | null {
  const at = src.indexOf(signature);
  if (at < 0) return null;
  const rest = src.slice(at);
  const end = rest.indexOf("\n}");
  return end < 0 ? rest : rest.slice(0, end + 2);
}

/**
 * **这一单送到哪儿 ≠ 我的默认收货地址。**
 *
 * <p>它们常常是同一条记录，于是「点一条就 setDefaultAddress，结算页读默认」
 * 是极自然的省事写法 —— 少一个接口、少一处状态，而且**看起来完全正常**。
 * 代价是给父母寄一次东西，从此每一单都预填父母家。
 *
 * <p>与 `active-address.test.ts` 守的是同一类错误（把两个概念接到一起），
 * 只是错在另一对概念上：那边是「生效位置 vs 默认」，这边是「这一单 vs 默认」。
 */
describe("结算页选地址 ≠ 改默认地址", () => {
  const addressPage = code("src/pages/address/index.vue");
  const confirmPage = code("src/pages/order-confirm/index.vue");

  it("★★★ 地址簿的 picking 分支不许改默认地址", () => {
    const body = bodyOf(addressPage, "function pick(");
    expect(body, "地址页没有 pick 函数了 —— 守卫失去了扫描对象，先修守卫再说").not.toBeNull();
    expect(body, "用「改默认」来传「这一单选谁」：长期偏好被一单改写")
      .not.toContain("setDefaultAddress");
  });

  it("★★★ 选中的那条要交回给结算页，而不是写进服务端", () => {
    const body = bodyOf(addressPage, "function pick(");
    expect(body).toContain("offerPickedAddress");
    expect(body, "交完就返回").toContain("navigateBack");
  });

  it("★★★ 结算页必须接住 —— 不接就等于他白选了一次", () => {
    expect(confirmPage).toContain("takePickedAddress");
    const body = bodyOf(confirmPage, "onShow(");
    expect(body, "结算页没有 onShow 了 —— 从地址簿返回时这一页不会更新").not.toBeNull();
    expect(body).toContain("takePickedAddress");
    // 接住之后要真的落到 addressId 上，否则拿到了也没用在这一单
    expect(body).toMatch(/addressId\.value = picked/);
  });

  it("★★ 返回时要重取地址簿 —— 他可能在那边新增了一条", () => {
    const body = bodyOf(confirmPage, "onShow(");
    expect(body).toContain("loadAddresses");
    /*
     * 顺序有讲究：新增的那条要先进 addresses，`address` 这个 computed 才找得到它。
     * 反过来写不会报错，只会让新地址「选了但页面上没变」。
     */
    expect(body).toMatch(/loadAddresses\(\)[\s\S]{0,200}addressId\.value = picked/);
  });

  it("★★★ 交接是一次性的：读一次就没了", () => {
    const mod = code("src/shared/address-pick.ts");
    /*
     * 不清掉的话，下一次进结算页会莫名其妙跳到上次选的那条 ——
     * 一个没人能复现、也没人会联想到这里的缺陷。
     */
    const body = bodyOf(mod, "export function takePickedAddress(");
    expect(body, "takePickedAddress 不见了").not.toBeNull();
    expect(body).toMatch(/pending = null/);
  });
});
