// 三语引擎：中 / 英 / 阿（ar 走 RTL 镜像，方向由 stores/app 驱动）。
//
// 两端的词条不同，但**创建实例、同步 shared 层当前语言、切语言**这三件事逐字相同。
// 放这里还有一个好处：`stores/app.ts` 能直接改语言，不必再让每个 app 注册一座桥 ——
// 桥是「库不认识 app 的 i18n 实例」时的将就办法，实例挪进来之后它就没有存在理由了。
import { createI18n } from "vue-i18n";
import { DEFAULT_LANG, STORAGE } from "@shared/utils/constants";
import { setCurrentLang } from "@shared/utils/locale";
import type { Lang } from "@shared/types";

/** 词条树：值可以是文案，也可以是更深一层的分组（`order.status.PAID`） */
type MessageTree = { [key: string]: string | MessageTree };
type Messages = Record<string, MessageTree>;

let instance: ReturnType<typeof createI18n> | undefined;

/** 在 app 的 `main.ts` 里调一次，把自己那套词条交进来 */
export function createAppI18n(messages: Messages) {
  const stored = (uni.getStorageSync(STORAGE.lang) as Lang) || (DEFAULT_LANG as Lang);
  instance = createI18n({
    legacy: false,
    globalInjection: true,
    locale: stored,
    fallbackLocale: DEFAULT_LANG,
    messages,
  });
  // shared 层（mock / 格式化）不依赖 vue-i18n 实例，靠这一处注入拿到当前语言
  setCurrentLang(stored);
  return instance;
}

export function setI18nLang(lang: Lang): void {
  if (!instance) return;
  (instance.global.locale as { value: string }).value = lang;
  setCurrentLang(lang);
}

/** 非组件上下文（mock/store）取当前语言 */
export { currentLang } from "@shared/utils/locale";
