import { describe, expect, it } from "vitest";
import { BUILD_BRAND, resolveBrand } from "./brand";

describe("品牌配置（brand.json）", () => {
  it("没有配置文件时用默认名称，登录页总有名称可显示", () => {
    expect(resolveBrand(null, "zh")).toEqual(BUILD_BRAND);
    expect(BUILD_BRAND.name).toBe("NearGo平台运营");
    expect(BUILD_BRAND.mark).toBe("N");
  });
  it("文件里的名称、标识与环境覆盖默认值", () => {
    expect(resolveBrand({ name: "某某运营", mark: "M", env: "test" }, "zh"))
      .toEqual({ ...BUILD_BRAND, name: "某某运营", mark: "M", env: "test" });
  });
  it("按语言覆盖，没写的语言用顶层值", () => {
    const file = { name: "NearGo平台运营", locales: { en: { name: "NearGo Ops" } } };
    expect(resolveBrand(file, "en").name).toBe("NearGo Ops");
    expect(resolveBrand(file, "zh-TW").name).toBe("NearGo平台运营");
  });
  it("写错的字段逐项回落，不让整份配置失效", () => {
    const b = resolveBrand({ name: 42, mark: "  ", env: "prod", logoUrl: "/logo.svg" }, "zh");
    expect(b).toEqual({ ...BUILD_BRAND, logoUrl: "/logo.svg" });
  });
  it("标识最多两个字符；非对象的文件整份忽略", () => {
    expect(resolveBrand({ mark: "NGO" }, "zh").mark).toBe("NG");
    expect(resolveBrand(["x"], "zh")).toEqual(BUILD_BRAND);
  });
});
