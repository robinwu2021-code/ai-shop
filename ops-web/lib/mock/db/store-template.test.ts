// 主页模板配置的规则测试（P-10.1.1）。
//
// 测的是「一改就影响一批店铺页」的那几条：停用还有店在用的模板、
// 关掉店招、以及把模板启用板块砍到只剩一个。
import { beforeEach, describe, expect, it } from "vitest";
import { storeMock } from "@/lib/api/mocks/store";
import { storeTemplates } from "@/lib/mock/db";
import { MIN_ENABLED_SECTIONS } from "@/lib/constants";

const snapshot = storeTemplates.map((t) => ({ ...t, sections: t.sections.map((s) => ({ ...s })) }));

beforeEach(() => {
  storeTemplates.splice(0, storeTemplates.length,
    ...snapshot.map((t) => ({ ...t, sections: t.sections.map((s) => ({ ...s })) })));
});

const base = () => {
  const t = storeTemplates.find((x) => !x.isDefault && x.usedByCount === 0)!;
  return { ...t, sections: t.sections.map((s) => ({ ...s })) };
};

describe("模板板块", () => {
  it("**必选板块（店招）不能停用** —— 关了之后店铺页没有头部，等于一张裸列表", async () => {
    const t = base();
    t.sections = t.sections.map((s) => (s.required ? { ...s, enabled: false } : s));
    await expect(storeMock.saveStoreTemplate(t)).rejects.toThrow(/必选板块/);
  });

  it(`启用的板块不能少于 ${MIN_ENABLED_SECTIONS} 个`, async () => {
    const t = base();
    t.sections = t.sections.map((s) => ({ ...s, enabled: s.required }));
    await expect(storeMock.saveStoreTemplate(t)).rejects.toThrow(/至少要启用/);
  });

  it("板块 key 重复要拒绝 —— 哪条生效取决于顺序，那是隐性行为", async () => {
    const t = base();
    t.sections = [...t.sections, { key: "HOT" as const, enabled: true, required: false }];
    await expect(storeMock.saveStoreTemplate(t)).rejects.toThrow(/板块重复/);
  });

  it("合法模板落库；新模板拿到新编号且引用计数从 0 起", async () => {
    const before = storeTemplates.length;
    const t = await storeMock.saveStoreTemplate({
      name: "测试模板", layout: "GRID", enabled: true, isDefault: false,
      sections: [
        { key: "BANNER", enabled: true, required: true },
        { key: "HOT", enabled: true, required: false },
      ],
    });
    expect(t.templateNo).toMatch(/^TPL\d+$/);
    expect(t.usedByCount).toBe(0);
    expect(storeTemplates.length).toBe(before + 1);
    // 真落库：重新读一次能找到（伪实现会在这里露馅）
    expect((await storeMock.listStoreTemplates()).some((x) => x.templateNo === t.templateNo)).toBe(true);
  });
});

describe("启用与停用", () => {
  it("**还有店在用的模板停不掉** —— 停用会让那些店铺页瞬间失去模板", async () => {
    const used = storeTemplates.find((t) => t.usedByCount > 0 && !t.isDefault)!;
    await expect(storeMock.setStoreTemplateEnabled(used.templateNo, false)).rejects.toThrow(/还有 \d+ 家店/);
  });

  it("默认模板停不掉 —— 新店开出来就用它", async () => {
    const def = storeTemplates.find((t) => t.isDefault)!;
    await expect(storeMock.setStoreTemplateEnabled(def.templateNo, false)).rejects.toThrow(/默认模板/);
  });

  it("没人用的模板可以停用，也可以再启用", async () => {
    const free = storeTemplates.find((t) => t.usedByCount === 0 && !t.isDefault)!;
    const off = await storeMock.setStoreTemplateEnabled(free.templateNo, true);
    expect(off.enabled).toBe(true);
    const on = await storeMock.setStoreTemplateEnabled(free.templateNo, false);
    expect(on.enabled).toBe(false);
    expect(on.updatedBy).toBe("admin");
  });
});
