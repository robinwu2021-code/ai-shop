// **首页不印位置信息。**（2026-09-18 产品决策）
//
// 此前首页顶栏写着「桂澜新村 · 点击选择你在哪儿」，还带一个「当前位置」标；
// 商品那一块的标题是「社区在卖 · 桂澜新村」。
//
// 改掉的理由：首页要回答的是**有什么可买**。货能不能送到你那儿，
// 看商品详情页上的销售范围 —— 顶栏那一行既猜不准（它只知道你此刻站在哪），
// 也替不了那个答案，而它摆在最显眼的位置，会被读成「这些货都能送到这儿」。
//
// **这条守卫是反向的**：不是「要显示什么」，是「不许再显示什么」。
// 没有它的话，下一次有人顺手把地名加回顶栏，没人记得当初为什么去掉。
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const home = readFileSync(join(import.meta.dirname, "../src/pages/home/index.vue"), "utf8");
/** 只看模板那一段 —— script 里读 location 是正常的（下单、归因都要） */
const template = home.slice(home.indexOf("<template>"));

describe("首页不声称你在哪", () => {
  it("★★★ 顶栏与商品块都不许印位置", () => {
    for (const [what, needle] of [
      ["顶栏的地名", "location.label"],
      ["「当前位置」标", "home.hereTag"],
      ["顶栏的位置副标题", "placeSub"],
      ["商品块的标题", "home.communityFeed\""],
      ["商品块的聚落名", "community.community?.name"],
    ] as const) {
      expect(template, `${what}又回到首页模板里了 —— 首页要回答的是「有什么可买」`)
        .not.toContain(needle);
    }
  });

  it("★★★ 但那一行必须还在，且点得进收货地址 —— 整行拿掉就没路管理地址了", () => {
    expect(template, "顶栏那一行空着，会让人以为页面没加载完")
      .toContain('$t("address.title")');
    expect(template, "那一行不再是去收货地址的入口").toContain('@tap="gotoPlace"');
    // 对照量：模板本身要扫得到，否则上面几条是在量空集
    expect(template.length, "没扫到模板 —— 这条守卫量的是空集").toBeGreaterThan(2000);
  });
});
