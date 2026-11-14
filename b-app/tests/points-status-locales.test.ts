import { expect, it } from "vitest";
import { pointsTypeStatusKey } from "@shared/utils/points-status";
import bzh from "../src/i18n/locale/zh-CN";
import ben from "../src/i18n/locale/en";
import bar from "../src/i18n/locale/ar";
import czh from "../../c-app/src/i18n/locale/zh-CN";
import cen from "../../c-app/src/i18n/locale/en";
import car from "../../c-app/src/i18n/locale/ar";
it("all supported languages resolve shared business status keys on both clients", () => {
 for (const dict of [bzh,ben,bar,czh,cen,car]) {
  for (const state of ["DRAFT","ACTIVE","DRAINING","RETIRED","UNKNOWN"]) {
   const key=pointsTypeStatusKey(state).slice("points.".length);
   const label=(dict.points as Record<string,unknown>)[key];
   expect(typeof label).toBe("string");expect(label).not.toBe(state);expect(String(label).trim()).not.toBe("");
  }
  expect((dict.points as Record<string,unknown>).typeStateEarningPaused).toBeTruthy();
 }
 expect(bzh.points.typeStateEarningPaused).toContain("新增赠送");
});
