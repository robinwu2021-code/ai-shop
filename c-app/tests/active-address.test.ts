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
 * 「当前生效位置」与「默认收货地址」**必须是两件事**。
 *
 * <p>它们常常是同一条记录，所以合成一个字段/一个按钮是极自然的省事写法 ——
 * 而合了之后，给父母下单的人就没法表达「切到父母家看货、但收货人还是我」。
 * 这类错误不会报错、不会崩，只会让一小撮用户的订单寄错地方。
 *
 * <p>用读源码的方式守：真正要防的是**有人把这两个动作接到一起**，
 * 那是一处结构性的改动，源码里看得见。
 */
describe("生效位置 ≠ 默认收货地址", () => {
  it("★★★ 切换生效位置的那条路，不许顺手改默认", () => {
    const store = code("src/stores/location.ts");
    expect(store, "location store 里出现 setDefaultAddress = 两个动作被接到了一起")
      .not.toContain("setDefaultAddress");
    expect(store).not.toContain("isDefault");
  });

  it("★★★ 地址页要有**两个**独立的动作按钮", () => {
    const page = code("src/pages/address/index.vue");
    expect(page, "「设为当前位置」").toContain("useHere");
    expect(page, "「设为默认」").toContain("setDefault");
    // 两个 handler 各自独立：任何一个调到另一个，就是把它们合并了
    expect(page).not.toMatch(/function useHere[\s\S]{0,200}setDefaultAddress/);
    expect(page).not.toMatch(/function setDefault[\s\S]{0,200}switchTo/);
  });

  it("★★ 没有生效位置时首页仍要有内容 —— 空顶栏会让人以为没加载完", () => {
    const home = code("src/pages/home/index.vue");
    /*
     * 顶栏那一行必须有回落链：生效位置 → 自提点 → 提示去选。
     * 只写第一段的话，新用户看到的是一行空白。
     */
    expect(home).toMatch(/location\.label \|\|[\s\S]{0,120}choosePickup/);
  });

  it("★★ 生效位置没有坐标时，不许清掉现有归属", () => {
    const store = code("src/stores/location.ts");
    /*
     * 微信地址簿导入的地址不带经纬度（chooseAddress 只给文字）。
     * 那种位置照样是有效收货地址，只是推不出社区 —— 清掉的话
     * 用户会发现「换了个地址，商品全没了」。
     */
    expect(store).toMatch(/latE6 == null \|\| a\.lngE6 == null\) return/);
  });
});
