// 「输入一行字」的弹层：`await prompt({...})`，用法与 `uni.showModal({editable:true})` 一样，
// 但字是我们自己的。
//
// **为什么要替掉 showModal**（`sh-sheet` 的类注释第一句就是这个）：
// 那是系统弹框，标题与输入框不是同一套字，字号、行高、对齐都不归我们管，
// 也不跟皮肤走。B 端有 26 个页面在用它，其中 11 处是带输入的。
//
// **更要命的是它的 `content` 有二义**：`editable: true` 时 `content` 不是说明文字，
// 而是**输入框的初始值**。`customers` 的代码里记着这个坑：
//
// > 试算结果放标题，不能放 content —— 放那儿的话，商家按下确定就存出一个
// > 叫「命中 1 人，其中 1 人能收到消息」的人群，而他并不觉得自己输了这行字。
//
// 写下这段的人修好了自己那一处，**而另外两处一直错着**（2026-08-26 扫出来）：
//   · `home` 的「快速开店」把整段「填个店名就能开张。执照以后在…」预填进输入框 ——
//     新商家直接按确定，店名就成了那一段话。**那是第一次开店的必经路径。**
//   · `goods-list` 的「改门店价」把「仅调整本门店售价，清空后与主体价一致」预填进去，
//     要先清空才能输价格，而 placeholderText 里的当前价永远不显示。
//
// 所以这里把两件事**拆成两个参数**：`hint` 是说明，`value` 是初值。
// 同一个坑再也长不出来 —— 不是靠注释提醒，是靠签名。
import { reactive } from "vue";

export interface PromptOptions {
  title: string;
  /** 说明文字。**不会**进输入框 */
  hint?: string;
  /** 输入框的初始值 */
  value?: string;
  placeholder?: string;
  confirmText?: string;
  cancelText?: string;
  /** 数字键盘。`digit` 带小数点（价格），`number` 是整数（库存） */
  type?: "text" | "number" | "digit";
  /** 密码：输入时打点 */
  password?: boolean;
  maxlength?: number;
}

interface PromptState extends PromptOptions {
  visible: boolean;
  input: string;
}

/** 弹层状态。**模块级单例**：同一时刻只可能有一个输入弹层 */
export const promptState = reactive<PromptState>({
  visible: false, title: "", input: "",
});

let settle: ((v: string | null) => void) | null = null;

/**
 * 弹出输入框。确定返回输入的字符串（**未 trim**，由调用方决定），取消返回 `null`。
 *
 * <p>返回 `null` 而不是空串：「取消」与「清空后确定」是两件事 ——
 * `goods-list` 的门店价就是靠「清空 = 与主体价一致」工作的。
 */
export function prompt(opts: PromptOptions): Promise<string | null> {
  // 上一个还开着就先收掉，别让两个叠在一起
  settle?.(null);
  Object.assign(promptState, {
    hint: "", value: "", placeholder: "", confirmText: "", cancelText: "",
    type: "text", password: false, maxlength: 0,
    ...opts,
    input: opts.value ?? "",
    visible: true,
  });
  return new Promise((resolve) => {
    settle = resolve;
  });
}

// ── 确认弹层 ────────────────────────────────────────────────────────
//
// `showModal` 的确认框有 25 处。它们的代价比带输入的那 12 处轻 ——
// 只是「字不是我们的」—— 但**有一件事系统弹框做不到**：
// 危险操作与普通确认在它那里长得一模一样，都是「取消 / 确定」两个蓝字。
//
// 而这套设计语言里危险操作是有专门一档的：`.sh-btn--danger-solid`
//（红实心，**只留给二次确认那一击**）。它此前**两端引用数是 0** ——
// 清单每次跑都报「定义了没人用」。不是没人需要，是没有地方用它：
// 二次确认全在系统弹框里，而那里没有我们的按钮。

export interface ConfirmOptions {
  title: string;
  /** 说明。确认框里它就是说明，没有 showModal 那种二义 */
  hint?: string;
  confirmText?: string;
  cancelText?: string;
  /** 危险操作：确定键用红实心。**这是系统弹框给不了的那一档** */
  danger?: boolean;
  /** 只有一个「知道了」（如「怎么升级」这类纯告知） */
  alert?: boolean;
}

interface ConfirmState extends ConfirmOptions {
  visible: boolean;
}

export const confirmState = reactive<ConfirmState>({ visible: false, title: "" });

let settleConfirm: ((v: boolean) => void) | null = null;

/** 确认。确定 `true`，取消 `false`。 */
export function confirm(opts: ConfirmOptions): Promise<boolean> {
  settleConfirm?.(false);
  Object.assign(confirmState, {
    hint: "", confirmText: "", cancelText: "", danger: false, alert: false,
    ...opts,
    visible: true,
  });
  return new Promise((resolve) => {
    settleConfirm = resolve;
  });
}

/** 由 sh-confirm 调用，页面不用管 */
export function closeConfirm(ok: boolean): void {
  confirmState.visible = false;
  const done = settleConfirm;
  settleConfirm = null;
  done?.(ok);
}

/** 由 sh-prompt 调用，页面不用管 */
export function closePrompt(value: string | null): void {
  promptState.visible = false;
  const done = settle;
  settle = null;
  done?.(value);
}
