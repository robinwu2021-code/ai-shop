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

/** vue-i18n 消息函数的上下文（只用到命名插值 `ctx.named`） */
type MsgCtx = { named?: (k: string) => unknown };

/**
 * 把词条里的普通字符串**预编译成「消息函数」**，函数内自己做 `{x}` 命名插值。
 *
 * 为什么不用 vue-i18n 的 `messageCompiler`（上一版的做法）：**uni-app 的 App 构建
 * 把 `vue-i18n` 别名成自带的极简 runtime**（`uni-cli-shared/lib/vue-i18n/…runtime…`），
 * 那个 runtime **不做运行时字符串编译、也直接丢弃 `messageCompiler` 选项** ——
 * 于是 `$t("login.resend",{s:5})` 在打包的 App 里显示字面 `{s}s 后重发`
 * （H5 正常、只在 App 裂；5.24 起尤其明显）。
 *
 * 实测这个 runtime **唯一**支持的插值形式就是消息函数（`createMessageContext`
 * 仍向函数提供 `ctx.named`）。用它 → H5 / 小程序 / App 三端一致。
 * 本项目词条只有命名插值 `{x}`（无复数 `|`、无链接 `@:`、无位置 `{0}`，已核对三份 locale），
 * 所以这个极简替换完整覆盖。
 */
function compileMessages(node: string | MessageTree): unknown {
  if (typeof node === "string") {
    const str = node;
    return (ctx: MsgCtx) =>
      str.replace(/\{(\w+)\}/g, (_, k) => {
        const v = ctx?.named ? ctx.named(k) : undefined;
        return v == null ? `{${k}}` : String(v);
      });
  }
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(node)) {
    const child = node[k];
    if (child !== undefined) {
      out[k] = compileMessages(child);
    }
  }
  return out;
}

/** 在 app 的 `main.ts` 里调一次，把自己那套词条交进来 */
export function createAppI18n(messages: Messages) {
  const stored = (uni.getStorageSync(STORAGE.lang) as Lang) || (DEFAULT_LANG as Lang);
  instance = createI18n({
    legacy: false,
    globalInjection: true,
    locale: stored,
    fallbackLocale: DEFAULT_LANG,
    // 词条预编译成消息函数（见 compileMessages）——App 端别名的极简 runtime 只认这一种插值
    messages: compileMessages(messages) as never,
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
