// 地名规则：**端上与后端必须是同一份词表**，外加几条出过事的具体用例。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这道闸在防什么
// ─────────────────────────────────────────────────────────────────────────────
// 「景滑村委会」（官方机构名）与「景滑」（商家从地图建档时随手起的名）是同一个地方。
// 判断「已经有了没」不能按原字符串比，要先去掉通名后缀 —— 而这段归一化
// **端上一份、后端一份**（`b-app/src/utils/region-names.ts` 与 `PlaceNames.java`）。
//
// <p>两边分叉不会报错，症状是**同一个地方在搜索结果里出现两次**：
// 一条走「已开通」直接勾，一条走官方名录「点了要建档」。真机上出过
// （搜「景滑村」两条），当时的根因正是「村委会」这一项只补了一边。
//
// <p>所以这里比的是**词表本身**，不是行为：行为一致可以靠巧合，词表一致不能。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  cleanVillageName, guessCityFrom, normalizeName, sameishName, shortName,
} from "../../../b-app/src/utils/region-names";

const ROOT = join(import.meta.dirname, "../../..");

/** 从一段源码里抠出那条「通名后缀」词表（两边都写成 `(a|b|c)+$`） */
function suffixWords(src: string, label: string): string[] {
  const start = src.indexOf("(小区|");
  const end = src.indexOf(")+$", start);
  if (start < 0 || end < 0) {
    throw new Error(`${label} 里找不到通名词表（形如 \`(小区|…)+$\`）—— 形状变了，先改这个解析器`);
  }
  const words = src.slice(start + 1, end).split("|");
  // 扫描面自己也要有断言：只抠到一两个词说明解析器错了，而那时这道闸会「全绿」
  if (words.length < 5) throw new Error(`${label} 只抠到 ${words.length} 个词，解析器坏了`);
  return words;
}

describe("地名归一化", () => {
  it("★★★ 端上与后端是同一份词表 —— 分叉了不报错，只是同一个地方出现两次", () => {
    const front = readFileSync(join(ROOT, "b-app/src/utils/region-names.ts"), "utf8");
    const back = readFileSync(
      join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/common/PlaceNames.java"),
      "utf8",
    );
    expect(
      suffixWords(front, "region-names.ts"),
      "端上 `normalizeName` 与后端 `PlaceNames.norm` 的通名词表对不上。\n"
      + "  两边都要改：改一边的后果是同一个聚落在搜索里出现两条"
      + "（一条「已开通」、一条「点了要建档」），而这不会报任何错。",
    ).toEqual(suffixWords(back, "PlaceNames.java"));
  });

  it("★★★ 「村委会」单独在词表里 —— 它不是「村」+「委会」拼得出来的", () => {
    // 这一条是真机故障的直接修复：漏了它，官方机构名整串穿过归一化
    expect(normalizeName("景滑村委会")).toBe("景滑");
    expect(sameishName("景滑村委会", "景滑")).toBe(true);
  });

  it("括号里的注记与通名后缀都去掉，剩下的才是「哪个地方」", () => {
    expect(normalizeName("阳光花园小区")).toBe("阳光");
    expect(normalizeName("阳光花园(北区)")).toBe("阳光");
    expect(normalizeName("阳光花园（北区）")).toBe("阳光");
    expect(sameishName("阳光花园", "阳光花园小区")).toBe(true);
  });

  it("整串都是通名的不算任何地方 —— 空串是谁的前缀都成立", () => {
    expect(normalizeName("村委会")).toBe("");
    // 认了空串会把「村委会」与随便哪个地方判成同一个
    expect(sameishName("村委会", "阳光花园")).toBe(false);
  });

  it("不同的地方不许判成同一个", () => {
    expect(sameishName("阳光花园", "月光花园")).toBe(false);
    expect(sameishName("牛杜村", "茜坑社区")).toBe(false);
  });
});

describe("官方名 → 商家嘴里的地名", () => {
  it("★★ 去掉的是「委员会」那一截，**通名要留着**", () => {
    // 一并吃掉的话：搜索里「牛杜」、名录里「牛杜村委会」、已开通里「牛杜村」，
    // 同一个地方三种写法，看着像三层
    expect(cleanVillageName("牛杜村委会")).toBe("牛杜村");
    expect(cleanVillageName("茜坑社区居委会")).toBe("茜坑社区");
    expect(cleanVillageName("富城村村民委员会")).toBe("富城村");
    expect(cleanVillageName("某某居民委员会")).toBe("某某社区");
  });

  it("已经是地名的原样返回", () => {
    expect(cleanVillageName("牛杜村")).toBe("牛杜村");
    expect(cleanVillageName("阳光花园")).toBe("阳光花园");
  });
});

describe("从关键词里抠市名", () => {
  it("★★ 带完整地址的写法要抠得出市名 —— 抠不出就退化成全国搜", () => {
    // city 传空串时高德在全国范围搜「福安雅园」，同名的、更有名的会把真的挤下去
    expect(guessCityFrom("深圳市龙华区福安雅园")).toBe("深圳市");
    expect(guessCityFrom("湘西土家族苗族自治州吉首")).toBe(undefined); // 超过 6 字，不猜
    expect(guessCityFrom("福安雅园")).toBe(undefined);
  });

  it("市名本身不算前缀（i > 0）—— 「市场街」不是一个市", () => {
    expect(guessCityFrom("市场街 12 号")).toBe(undefined);
  });
});

describe("路径取末级", () => {
  it("提示语里只放末级，整条路径会把话挤没", () => {
    expect(shortName("浙江省 / 杭州市 / 西湖区")).toBe("西湖区");
    expect(shortName("西湖区")).toBe("西湖区");
    expect(shortName(undefined)).toBe("");
  });
});
