import type { I18nText } from "@shared/types";

/**
 * 编辑页回显三语原文。
 *
 * **后端对「没有三语」给的是 `{}` 不是 null**（`readMap` 空串回 `Map.of()`），
 * 而此前页面判的是 `g.titleI18n ? … : 回落 title` —— 空对象为真，回落那支永远走不到。
 * 于是只有拍平 `title` 的商品（接口直建、老数据）打开编辑页名称是空的，
 * 商家一保存就把名称存成空串。
 *
 * 规则：三语有值的格子照搬；**当前语言那一格空着时用拍平的那份补上** ——
 * 那是能拿到的全部信息，与原先注释里写的意图一致。
 */
export function mergeI18nText(
  base: I18nText,
  i18n: Partial<I18nText> | null | undefined,
  lang: keyof I18nText,
  flat: string | null | undefined,
): I18nText {
  const out: I18nText = { ...base };
  for (const k of Object.keys(i18n ?? {}) as Array<keyof I18nText>) {
    const v = i18n?.[k];
    if (typeof v === "string") out[k] = v;
  }
  if (!out[lang] && flat) out[lang] = flat;
  return out;
}
