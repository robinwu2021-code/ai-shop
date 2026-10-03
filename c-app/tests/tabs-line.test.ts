/**
 * sh-tabs 的「页签」变体（line）：门店门户的「商品 / 评价 / 店铺」。
 *
 * 钉的是「两种样子」：同一屏上页签与分类筛选都是 sh-tabs，
 * 页签要是 chip 就分不出哪排是换页、哪排是筛选（2026-09-29 用户：「整体布局不够精细」）。
 */
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ShTabs from "@ai-shop/ui/components/sh-tabs.vue";

const items = [
  { key: "goods", label: "商品" },
  { key: "reviews", label: "评价" },
  { key: "info", label: "店铺" },
];

describe("sh-tabs line", () => {
  it("★★★ line：文字 + 当前项一道短线，不画 chip", () => {
    const w = mount(ShTabs, { props: { items, active: "reviews", line: true } });
    expect(w.find(".tabs--line").exists()).toBe(true);
    expect(w.findAll(".sh-chip"), "页签画成 chip 就和分类分不开").toHaveLength(0);
    const on = w.findAll(".tabs__line.is-on");
    expect(on).toHaveLength(1);
    expect(on[0]!.text()).toBe("评价");
  });

  it("★★ 不传 line：仍是筛选 chip（别的页面不受影响）", () => {
    const w = mount(ShTabs, { props: { items, active: "goods" } });
    expect(w.find(".tabs--line").exists()).toBe(false);
    expect(w.findAll(".sh-chip")).toHaveLength(3);
  });

  it("★★ 点一项发 change，带它的 key", async () => {
    const w = mount(ShTabs, { props: { items, active: "goods", line: true } });
    await w.findAll(".tabs__line")[2]!.trigger("tap");
    expect(w.emitted("change")?.[0]).toEqual(["info"]);
  });
});
