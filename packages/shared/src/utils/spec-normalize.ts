/**
 * 规格文案的规范化。**入库前必过这一道。**
 *
 * 同一个规格，三家店会写成「500g」「500 G」「５００ｇ」「五百克」——
 * 归一库的全部价值建立在「同一件事只有一条记录」上，而没有这一步，
 * 值池会在三个月内长出一堆看着不同、其实一样的行，每一条都合法、都不报错。
 *
 * **后端也有一份同样的实现**（`SpecNormalizer.java`）：只在一侧做，另一侧就是漏网的入口
 * —— 商家在 b-app 输入的、运营在 ops-web 输入的，走的是两条路。
 * 两份实现由 `spec-normalize.test.ts` 用同一张用例表钉住。
 *
 * 这一步**只规范形式，不改语义**：「1斤」不会被改写成「500g」——
 * 那是别名与归一量该管的事，在这里做会把商家想说的话改掉。
 */

/** 中文数字 → 阿拉伯。只覆盖规格里真会出现的那几个 */
const CN_DIGITS: Record<string, string> = {
  〇: "0", 零: "0", 一: "1", 二: "2", 三: "3", 四: "4",
  五: "5", 六: "6", 七: "7", 八: "8", 九: "9",
};

/** 数字后面这些才算单位 —— 用来决定「500 g」中间那个空格该不该吃掉 */
const UNIT_AFTER =
  "g|kg|ml|l|cm|mm|m|w|寸|斤|克|千克|升|毫升|厘米|米|支|件|盒|袋|人|分钟|小时";

/**
 * 规范化一个规格文案（维度名、值文案、别名都走它）。
 *
 * 1. 去首尾空白，中间连续空白压成一个
 * 2. 全角转半角：`５００ｇ → 500g`
 * 3. 单位统一小写：`500G → 500g`；**升是例外**，国际符号就是大写 `L`——
 *    小写 `l` 在多数字体里与数字 1 分不开，而规格是要被人一眼读准的东西
 * 4. 数字与单位之间不留空格：`500 g → 500g`
 * 5. 中文数字转阿拉伯：`五斤 → 5斤`
 */
export function normalizeSpecLabel(raw: string | null | undefined): string {
  if (raw == null) return "";
  let s = raw.trim();
  if (!s) return "";

  s = [...s]
    .map((ch) => {
      const code = ch.codePointAt(0)!;
      // 全角 ！-～ → 半角
      if (code >= 0xff01 && code <= 0xff5e) return String.fromCodePoint(code - 0xfee0);
      if (ch === "　") return " ";           // 全角空格不在那段连续区里
      return CN_DIGITS[ch] ?? ch;
    })
    .join("");

  s = s.replace(/\s+/g, " ").trim();
  s = s.replace(new RegExp(`(?<=\\d) +(?=(?:${UNIT_AFTER})\\b|$)`, "iu"), "");
  return unifyUnit(s);
}

function unifyUnit(s: string): string {
  return s
    .replace(/(?<=\d)\s*kg\b/gi, "kg")
    .replace(/(?<=\d)\s*g\b/gi, "g")
    .replace(/(?<=\d)\s*ml\b/gi, "ml")
    .replace(/(?<=\d)\s*l\b/gi, "L")
    .replace(/(?<=\d)\s*cm\b/gi, "cm")
    .replace(/(?<=\d)\s*mm\b/gi, "mm")
    .replace(/(?<=\d)\s*w\b/gi, "W");
}
