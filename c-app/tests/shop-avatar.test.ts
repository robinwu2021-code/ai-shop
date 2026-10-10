/**
 * 店铺头像与自营标。
 *
 * 此前六处都把 logo 当**文字**打印（`{{ logo || 🏪 }}`）：一排店全是同一个表情；
 * 商家一旦传了图片 logo，还会把 https 地址按大字号铺出来。
 * 自营标在组件里早就写好了，但商品接口从来不带 selfOperated，一次都没显示过（后端那半边
 * 由 MerchantSelfOperatedFlowTest 守）。
 */
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import Avatar from "@/components/biz/biz-shop-avatar.vue";

const mk = (props: Record<string, unknown>) => mount(Avatar, { props });

describe("店铺头像", () => {
  it("★★★ logo 是图片地址 → 显示图片，不把地址当文字打出来", () => {
    const w = mk({ name: "虹选鲜果", logo: "https://cdn.example.com/a.png" });
    expect(w.find("image").attributes("src")).toBe("https://cdn.example.com/a.png");
    expect(w.text()).not.toContain("https://");
  });

  it("★★★ 自营店没有图片 logo → 用虹选品牌头像", () => {
    const w = mk({ name: "虹选鲜果", logo: "", selfOperated: true });
    expect(w.find("image").attributes("src")).toBe("/static/brand/store-self.png");
  });

  it("★★★ 第三方店没有图片 logo → 店名首字，不再用 🏪", () => {
    const w = mk({ name: "阿明果蔬合作社", logo: "🏪", selfOperated: false });
    expect(w.find("image").exists()).toBe(false);
    expect(w.text()).toBe("阿");
  });

  it("★★ 英文店名取首字母大写", () => {
    expect(mk({ name: "sunnyside", logo: "" }).text()).toBe("S");
  });

  it("★★ 品牌头像文件真的在包里 —— 路径对了文件不在，真机上就是一块空白", async () => {
    const { existsSync } = await import("node:fs");
    const { resolve } = await import("node:path");
    expect(existsSync(resolve(__dirname, "../src/static/brand/store-self.png"))).toBe(true);
  });
});
