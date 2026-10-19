import { expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { POINTS_AR } from "./points-ar";
import { pointsAcceptLanguage, usePointsLanguage } from "./points-language";
import { FINANCE_COPY } from "../app/finance/copy";
it("has Arabic for every points workspace label and explanation", () => {
  const english = new Set<string>();
  for (const file of readdirSync("app/points").filter((f) => f.endsWith("-tab.tsx"))) {
    const source = readFileSync(`app/points/${file}`, "utf8");
    for (const m of source.matchAll(/(?:t|text)\("(?:[^"\\]|\\.)*",\s*("(?:[^"\\]|\\.)*")\)/g)) english.add(JSON.parse(m[1]));
  }
  for (const [key, value] of Object.entries(FINANCE_COPY.en)) if (/^(pt|pp)/.test(key)) english.add(value);
  for (const value of english) expect(POINTS_AR[value], value).toMatch(/[\u0600-\u06ff]/);
});
it("sends Arabic only for the points endpoints while the workspace selects Arabic", () => {
  usePointsLanguage.setState({ arabic: true });
  expect(pointsAcceptLanguage("/ops/points/requests", "en")).toBe("ar");
  expect(pointsAcceptLanguage("/ops/orders", "zh-CN")).toBe("zh-CN");
  usePointsLanguage.setState({ arabic: false });
  expect(pointsAcceptLanguage("/ops/points/requests", "en")).toBe("en");
});
