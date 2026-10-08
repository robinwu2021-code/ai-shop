import { beforeEach, describe, expect, it, vi } from "vitest";
import { pickedAt, rememberStore, storeChoicesFor } from "@/shared/store-choice";

/*
 * 2026-10-09 线上：一个主体（虹选鲜果 / 虹选粮油）四家店各卖各的。买家在鲜果门户买过柿子，
 * 再从首页点进只在粮油上架的盐 —— 结算带着鲜果店去预览，70076 已下架，运费退回写死的 6 元。
 */
describe("store-choice · 在哪家店挑的就落哪家", () => {
  let box: Record<string, unknown>;
  beforeEach(() => {
    box = {};
    const u = (globalThis as unknown as { uni: Record<string, unknown> }).uni;
    u.getStorageSync = vi.fn((k: string) => box[k] ?? "");
    u.setStorageSync = vi.fn((k: string, v: unknown) => { box[k] = v; });
  });

  it("不是从门户点进来挑的货：忘掉这个主体记着的店", () => {
    rememberStore("M1", "ST-FRUIT");
    pickedAt("M1", "");
    expect(storeChoicesFor(["M1"])).toBeUndefined();
  });

  it("从门户点进来挑的货：记成这一家", () => {
    rememberStore("M1", "ST-FRUIT");
    pickedAt("M1", "ST-GRAIN");
    expect(storeChoicesFor(["M1"])).toEqual([{ merchantNo: "M1", storeNo: "ST-GRAIN" }]);
  });

  it("只动这一个主体，别家的记录不受影响", () => {
    rememberStore("M1", "ST-FRUIT");
    rememberStore("M2", "ST-OTHER");
    pickedAt("M1", undefined);
    expect(storeChoicesFor(["M1", "M2"])).toEqual([{ merchantNo: "M2", storeNo: "ST-OTHER" }]);
  });
});
