"use client";

// 统一确认弹窗（删除/高危操作）。刻意做成 hook 而非 Provider：
// 静态导出 SPA，页面各自渲染 {dialog} 即可，不牵动 app/providers.tsx。
//
//   const { confirm, dialog } = useConfirm();
//   const ok = await confirm({ title: "删除告警代码", danger: true, requireText: "OFFLINE" });
//   ... 页面里渲染 {dialog}
//
// 不可轻易撤销的动作（归档/封禁/退款）建议改传 action，由弹窗自己跑完：
//   await confirm({ title: "归档商家", action: () => archive.mutateAsync(no) });
import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Button } from "./button";
import { Input } from "./input";
import { useI18n } from "@/lib/i18n";

export interface ConfirmOptions {
  title: string;
  desc?: string;
  /** 危险操作：确认按钮用 destructive 配色 */
  danger?: boolean;
  confirmText?: string;
  cancelText?: string;
  /** 高危项：要求输入完全一致的文本才启用确认 */
  requireText?: string;
  /**
   * 传了它，弹窗就**自己把这件事做完**：点确认后弹窗不关，确认按钮转圈，
   * 直到 promise 落定才关闭（失败则留在原地，让人看得到刚才那句提示）。
   *
   * 为什么需要：不传 action 时弹窗点完就关，异步写在页面里跑 ——
   * 这段时间界面上没有任何"正在处理"的痕迹，用户会再点一次那个按钮。
   * 归档/封禁/退款这类**不可轻易撤销**的动作应当传 action。
   */
  action?: (reason: string) => Promise<unknown>;
  /**
   * 要求填一句**理由**，并把它交给 {@link ConfirmOptions.action}。
   *
   * 与 {@link ConfirmOptions.requireText} 不是一回事：那个是「照抄这串字确认你看清了」，
   * 这个是**要留档的一句话**，后端把它写进审计（停券、停活动、平台改价都要求它，
   * 空理由直接 10400）。
   *
   * 为什么要在弹窗里而不是各页自己做一个输入框：这类操作有 4 处，
   * 各写一遍的结果是「活动能填理由、券不能」——而后端对两者的要求一模一样。
   */
  requireReason?: boolean;
}

interface Pending extends ConfirmOptions {
  resolve: (ok: boolean) => void;
}

export function useConfirm() {
  const { t } = useI18n();
  const [pending, setPending] = React.useState<Pending | null>(null);
  const [typed, setTyped] = React.useState("");
  const [reason, setReason] = React.useState("");
  const [running, setRunning] = React.useState(false);

  // resolve 存 ref：state updater 必须是纯函数（StrictMode 下会执行两次），
  // 在其中调 resolve 属副作用，故把回调移出 updater。
  const resolveRef = React.useRef<((ok: boolean) => void) | null>(null);

  const confirm = React.useCallback((opts: ConfirmOptions) => {
    setTyped("");
    setReason("");
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
      setPending({ ...opts, resolve });
    });
  }, []);

  const close = React.useCallback((ok: boolean) => {
    const resolve = resolveRef.current;
    resolveRef.current = null;
    setPending(null);
    setTyped("");
    setReason("");
    setRunning(false);
    resolve?.(ok);
  }, []);

  /** 点「确认」。有 action 就地跑完再关；没有就是原来的行为（立刻关、返回 true）。 */
  const onConfirm = React.useCallback(async () => {
    const act = pending?.action;
    if (!act) { close(true); return; }
    setRunning(true);
    try {
      await act(reason.trim());
      close(true);
    } catch {
      // 失败不关：把弹窗留在原地，用户能看到页面上的错误提示，也能再试一次
      setRunning(false);
    }
  }, [pending, close, reason]);

  // requireText 存在时必须完全一致（不 trim、不忽略大小写）
  // requireReason 时理由不能是空白 —— 后端那条校验一样是 trim 之后判空
  const locked =
    (!!pending?.requireText && typed !== pending.requireText) ||
    (!!pending?.requireReason && !reason.trim());

  const dialog = (
    <Dialog.Root
      open={!!pending}
      // ESC / 点遮罩 关闭 = 取消
      // 执行中不许 ESC / 点遮罩关闭：请求已经发出去了，关掉只会让人以为没做
      onOpenChange={(o) => { if (!o && !running) close(false); }}
    >
      <Dialog.Portal>
        {/* data-[state=open]: 前缀会让 animate-in/fade-in/zoom-in 这几个纯 CSS 类完全生成不出规则
            （Tailwind 把它们当未知工具类丢弃），Content 只在 open 时挂载，去掉前缀即可，见 drawer.tsx 同注释 */}
        <Dialog.Overlay className="fixed inset-0 z-[var(--z-dialog)] bg-black/40 animate-in fade-in" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-[var(--z-dialog)] w-[min(92vw,400px)] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-sheet bg-card shadow-pop outline-none animate-in zoom-in">
          <div className="p-5">
            <Dialog.Title className="txt-heading">{pending?.title ?? ""}</Dialog.Title>
            {pending?.desc && (
              <Dialog.Description className="mt-1.5 txt-body text-muted-foreground">{pending.desc}</Dialog.Description>
            )}
            {pending?.requireReason && (
              <div className="mt-4">
                <div className="mb-1.5 txt-caption text-muted-foreground">
                  {t("confirm.reasonHint")}
                </div>
                <Input
                  autoFocus
                  value={reason}
                  placeholder={t("confirm.reasonPlaceholder")}
                  onChange={(e) => setReason(e.target.value)}
                />
              </div>
            )}
            {pending?.requireText && (
              <div className="mt-4">
                <div className="mb-1.5 txt-caption text-muted-foreground">
                  {t("confirm.requireHint", { text: pending.requireText })}
                </div>
                <Input
                  autoFocus
                  value={typed}
                  placeholder={pending.requireText}
                  onChange={(e) => setTyped(e.target.value)}
                />
              </div>
            )}
          </div>
          <div className="flex justify-end gap-2 bg-muted/40 p-4">
            <Button size="sm" variant="secondary" disabled={running} onClick={() => close(false)}>
              {pending?.cancelText ?? t("confirm.cancel")}
            </Button>
            <Button
              size="sm"
              variant={pending?.danger ? "destructive" : "default"}
              disabled={locked}
              loading={running}
              onClick={onConfirm}
            >
              {pending?.confirmText ?? t("confirm.ok")}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );

  return { confirm, dialog };
}
