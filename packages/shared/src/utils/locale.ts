// 当前语言的唯一持有者。
//
// 为什么不直接 import 各端的 i18n 实例：shared 被 C 端与 B 端同时引用，
// 若它反向依赖 `@/i18n`，就等于给每个使用方强加一个「必须存在同名模块且导出同名函数」
// 的隐式契约 —— 少一个端就是运行时才炸。这里改成显式注入：各端在 i18n 初始化与
// 切换语言时调用 setCurrentLang，shared 只读不写。
import { DEFAULT_LANG } from "@shared/utils/constants";
import type { Lang } from "@shared/types";

let current: Lang = DEFAULT_LANG as Lang;

export function setCurrentLang(lang: Lang): void {
  current = lang;
}

/** 非组件上下文（mock / 格式化工具）取当前语言 */
export function currentLang(): Lang {
  return current;
}
