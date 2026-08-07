"use client";

// 页面文案的中英对照。
//
// **为什么不塞进 `lib/i18n/messages/*.ts`**：那份是**框架层**文案（按钮、分页、
// 状态枚举），全站共用、条目稳定。页面正文是另一回事 —— 944 条、按页聚集、
// 改页面时多半要一起改。放同一个大文件里有两个坏处：
//   1. 一千多行的 catalog，改 A 页的人要在里面翻 B 页的 key
//   2. 看页面时看不到文案，看文案时看不到页面，对不上就会译歪
// 所以按页就近放：`app/xxx/copy.ts` 与 `app/xxx/page.tsx` 挨着。
//
// 约束由 `lib/page-copy.test.ts` 锁：zh/en 的 key 必须齐平、英文里不许残留汉字、
// 页面 JSX 里不许再出现裸中文。
import { useI18n } from "@/lib/i18n";

/** 一页的文案表：两种语言，key 必须完全一致（测试会比对）。 */
export interface PageCopy<T extends Record<string, string>> {
  zh: T;
  en: T;
}

/**
 * 取当前语言的这一页文案。
 *
 * 缺语言时回落 zh 而不是报错：漏译只该让界面出现中文，不该让页面白屏。
 */
export function useCopy<T extends Record<string, string>>(dict: PageCopy<T>): T {
  const { locale } = useI18n();
  return (dict as unknown as Record<string, T>)[locale] ?? dict.zh;
}

/** 带占位符的文案：`fill(c.totalN, { n: 5 })`。与 i18n 的 `t()` 用同一套 `{name}` 语法。 */
export function fill(raw: string, params: Record<string, string | number>): string {
  return raw.replace(/\{(\w+)\}/g, (_, k) => (params[k] != null ? String(params[k]) : `{${k}}`));
}
