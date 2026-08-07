// 消费者端词条。引擎（createI18n、切语言、同步 shared 层当前语言）在 @ai-shop/ui/i18n，
// 这里只负责把自己这三份词条交进去 —— 两端词条不同，装配方式相同。
import { createAppI18n } from "@ai-shop/ui/i18n";
import zhCN from "./locale/zh-CN";
import en from "./locale/en";
import ar from "./locale/ar";

export const i18n = createAppI18n({ "zh-CN": zhCN, en, ar });

export { setI18nLang, currentLang } from "@ai-shop/ui/i18n";
