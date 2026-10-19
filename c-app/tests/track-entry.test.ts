import { describe, expect, it } from "vitest";
import { trackEntryPath } from "@/shared/track-entry";

describe("URL Link 首页看件入口", () => {
  it("把签名票据交给小程序看件页", () => {
    expect(trackEntryPath("abc_DEF.123-x")).toBe("/pages/track/index?t=abc_DEF.123-x");
  });

  it("拒绝空值和路径注入", () => {
    expect(trackEntryPath(undefined)).toBeNull();
    expect(trackEntryPath("../pages/me/index")).toBeNull();
  });
});
