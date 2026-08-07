// 应用级 store：语言（中/英/阿）+ RTL 方向。切换即时生效并持久化。
import { defineStore } from "pinia";
import { DEFAULT_LANG, LANGS, STORAGE } from "@shared/utils/constants";
import { setI18nLang } from "../i18n";
import { applyDirection } from "@shared/ports/direction";
import type { Lang } from "@shared/types";

function isRtl(lang: Lang): boolean {
  return LANGS.find((l) => l.id === lang)?.rtl ?? false;
}

export const useAppStore = defineStore("app", {
  state: () => ({
    lang: DEFAULT_LANG as Lang,
  }),

  getters: {
    isRtl: (s) => isRtl(s.lang),
    /** 小程序端由 sh-scaffold 挂到根 view（无 documentElement 可写 dir） */
    dirClass(): string[] {
      return this.isRtl ? ["is-rtl"] : [];
    },
  },

  actions: {
    init() {
      this.lang = (uni.getStorageSync(STORAGE.lang) as Lang) || (DEFAULT_LANG as Lang);
      setI18nLang(this.lang);
      applyDirection(this.lang, this.isRtl);
    },
    setLang(lang: Lang) {
      this.lang = lang;
      uni.setStorageSync(STORAGE.lang, lang);
      setI18nLang(lang);
      applyDirection(lang, isRtl(lang));
    },
  },
});
