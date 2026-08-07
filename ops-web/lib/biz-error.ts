// 业务规则报错（双语）。
//
// 放在 lib 根而不是 lib/api/mocks/ 下：`lib/mock/db/helpers.ts` 的状态机守卫也要用它，
// 而 db 层不该反过来依赖 api 层。
//
// **为什么需要它**：mock 层的规则报错原先一律是中文（`throw new Error("...")`），
// 界面切到 EN 后，页面文案是英文、错误提示还是中文 —— 中英混排，而错误提示恰恰是
// 用户最需要看懂的那句话。
//
// **为什么是 `fail(zh, en)` 而不是 i18n key**：这些句子都是一次性的、带上下文的
// 具体说明（"渠道有、平台无的差异必须选择处置方式：补单或退款"），不是可复用的短语。
// 给每条起一个 key，只会得到 250 个只被引用一次的 key，以及一个必须来回跳的目录。
//
// **它与真实后端同构**：`http-client.ts` 发 `Accept-Language`，后端按语言返回本地化的
// `msg`，前端原样显示。mock 在这里做的是同一件事 —— 在**抛出的那一刻**按当前语言定稿。
// 因此错误提示与真实后端一样，切换语言不会改写已经弹出的那条 toast。
import { useLocaleStore } from "@/lib/stores/locale";
import { ApiError } from "./api/error";

/**
 * 抛一条业务规则错误。
 *
 * @param zh 中文说明。**要写清后果**，不要只说"参数不合法"
 * @param en 英文说明。与 zh 同义，不是逐字直译 —— 英文用英文的说法
 *
 * 返回类型是 `never`，所以 `if (bad) fail(...)` 之后 TS 知道后面的代码不可达。
 */
export function fail(zh: string, en: string): never {
  // 400：业务规则拒绝（与后端 ErrorCode 对齐；404 那类由调用方自己给）
  throw new ApiError(400, useLocaleStore.getState().locale === "en" ? en : zh);
}

/** 资源不存在。这一类的句式高度一致，单独给一个，省得 250 处各写各的。 */
export function notFound(zhEntity: string, enEntity: string, no: string): never {
  throw new ApiError(404, useLocaleStore.getState().locale === "en"
    ? `${enEntity} not found: ${no}`
    : `${zhEntity}不存在：${no}`);
}
