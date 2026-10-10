// **首页顶栏说「你此刻在哪」，商品块不说「哪个社区在卖」。**
//
// 两次改动叠出来的这条线，分开记会读反，所以写在一起：
//
// 1) 2026-09-18 上午：商品那一块的标题「社区在卖 · 桂澜新村」去掉了。
//    首页要回答的是**有什么可买**；货能不能送到你那儿，看商品详情页上的销售范围。
//    那个标题摆在商品上方，会被读成「这些货都能送到这儿」，而它保证不了这件事。
//
// 2) 同日下午：顶栏一度被换成固定文案「收货地址」—— **那一版是错的**，
//    它把入口的名字印在了本该显示状态的位置上：每一屏都一样，谁看都不知道自己在哪儿。
//    顶栏显示的是**当前定位**（`location.label`）：这一带支持快递外送，
//    「我此刻在哪」与「货寄到哪」是两件事，顶栏回答前一件。
//
// 所以这条守卫有方向：**顶栏要有地名，商品块不许有聚落名**。
// 把两者都禁掉（或都放开）都会退回被否过的某一版。
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const home = readFileSync(join(import.meta.dirname, "../src/pages/home/index.vue"), "utf8");
/** 只看模板那一段 —— script 里读 location 是正常的（下单、归因都要） */
const template = home.slice(home.indexOf("<template>"));

describe("首页顶栏说定位、商品块不说社区", () => {
  it("★★★ 商品块不许再印聚落名 —— 它会被读成「这些货都送得到这儿」", () => {
    for (const [what, needle] of [
      ["商品块的标题", 'home.communityFeed"'],
      ["商品块的聚落名", "community.community?.name"],
      ["顶栏的位置副标题", "placeSub"],
      ["「当前位置」标", "home.hereTag"],
    ] as const) {
      expect(template, `${what}又回到首页模板里了 —— 首页要回答的是「有什么可买」`)
        .not.toContain(needle);
    }
  });

  it("★★★ 顶栏要显示当前定位，不是「收货地址」四个字", () => {
    expect(template, "顶栏又变回固定文案了 —— 那一版每屏都一样，看不出自己在哪儿")
      .toContain("location.label");
    // 回落文案要留着：一个位置都取不到时那一行不能空，空着像没加载完
    expect(template, "取不到位置时没有回落文案").toContain('$t("address.title")');
    expect(template, "那一行不再是去收货地址的入口").toContain('@tap="gotoPlace"');
    // 对照量：模板本身要扫得到，否则上面几条是在量空集
    expect(template.length, "没扫到模板 —— 这条守卫量的是空集").toBeGreaterThan(2000);
  });
});
