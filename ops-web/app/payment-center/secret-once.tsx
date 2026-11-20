"use client";

// 一次性明文（终端激活码、应用密钥）：说明 → 点按钮生成 → 只在这个抽屉里显示一次 + 复制。
// 不进草稿、不进查询缓存、不写日志；关掉抽屉组件卸载，明文随之丢弃。
import * as React from "react";
import { Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { DrawerFooter } from "@/components/ui/drawer";
import { notify } from "@/lib/notify";
import { errorText, usePc } from "./shared";

export function SecretOnce({ note, button, shownHint, generate, extra, onDone }: {
  note: string; button: string; shownHint: string;
  generate: () => Promise<{ secret: string; meta?: React.ReactNode }>;
  extra?: React.ReactNode;
  onDone: () => void;
}) {
  const c = usePc();
  const [result, setResult] = React.useState<{ secret: string; meta?: React.ReactNode } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const go = async () => {
    setBusy(true);
    try { setResult(await generate()); } catch (e) { notify.error(errorText(e, c)); } finally { setBusy(false); }
  };
  return (
    <>
      <Notice tone="warning" className="mb-4">{note}</Notice>
      {extra}
      {result && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 rounded-field bg-muted p-3">
            <code className="min-w-0 flex-1 break-all font-mono txt-strong" data-secret>{result.secret}</code>
            <Button size="sm" variant="outline" onClick={() => { void navigator.clipboard?.writeText(result.secret).then(() => notify.success(c.done), () => {}); }}>
              <Copy className="size-3.5" />
            </Button>
          </div>
          <p className="txt-caption text-muted-foreground">{shownHint}</p>
          {result.meta}
        </div>
      )}
      <DrawerFooter>
        <Button variant="outline" onClick={onDone}>{c.done}</Button>
        {!result && <Button loading={busy} onClick={() => void go()}>{button}</Button>}
      </DrawerFooter>
    </>
  );
}
