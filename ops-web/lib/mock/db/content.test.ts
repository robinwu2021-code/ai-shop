// 素材中心规则测试（P-15.1）。核心只有一条：「投给谁」必须明确。
import { beforeEach, describe, expect, it } from "vitest";
import { contentMock } from "@/lib/api/mocks/content";
import { materials } from "./content";

const M0 = JSON.parse(JSON.stringify(materials)) as typeof materials;
beforeEach(() => {
  materials.length = 0; materials.push(...(JSON.parse(JSON.stringify(M0)) as typeof materials));
});

describe("可见范围（P-15.1.3 / 15.1.4）", () => {
  it("指定社区却不给社区列表被拒 —— 这份素材谁都看不到，但列表里显示得好好的", async () => {
    await expect(
      contentMock.saveMaterial({ materialNo: "", title: "海报", kind: "POSTER", content: "url", scope: "COMMUNITY", scopeRefs: [], langs: [] }),
    ).rejects.toThrow(/至少一个对象/);
  });

  it("指定商家同理", async () => {
    await expect(
      contentMock.saveMaterial({ materialNo: "", title: "视频", kind: "VIDEO", content: "url", scope: "MERCHANT", scopeRefs: [], langs: [] }),
    ).rejects.toThrow(/至少一个对象/);
  });

  it("全部商家不需要列表", async () => {
    const m = await contentMock.saveMaterial({ materialNo: "", title: "通用文案", kind: "COPY", content: "今日团…", scope: "ALL", scopeRefs: [], langs: [] });
    expect(m.scope).toBe("ALL");
    expect(m.published).toBe(false);
  });

  it("标题与内容必填", async () => {
    await expect(
      contentMock.saveMaterial({ materialNo: "", title: "", kind: "COPY", content: "x", scope: "ALL", scopeRefs: [], langs: [] }),
    ).rejects.toThrow(/标题必填/);
    await expect(
      contentMock.saveMaterial({ materialNo: "", title: "x", kind: "COPY", content: "", scope: "ALL", scopeRefs: [], langs: [] }),
    ).rejects.toThrow(/内容必填/);
  });

  it("按范围筛选", async () => {
    const page = await contentMock.listMaterials({ scope: "COMMUNITY", size: 100 });
    expect(page.records.every((m) => m.scope === "COMMUNITY")).toBe(true);
  });
});
