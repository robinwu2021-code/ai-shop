import { expect, it } from "vitest";
import { pointsAcceptLanguage } from "./points-language";

it("uses the shared system language for points API requests", () => {
  expect(pointsAcceptLanguage("/ops/points/requests", "en")).toBe("en");
  expect(pointsAcceptLanguage("/ops/points/requests", "zh-CN")).toBe("zh-CN");
});
