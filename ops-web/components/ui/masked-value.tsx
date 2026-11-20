"use client";

// 敏感值掩码显示：默认只露后 4 位；有权限的人可点「显示」看全，onReveal 用于记审计。
import * as React from "react";
import { useI18n } from "@/lib/i18n";

export function maskValue(value: string, tail = 4): string {
  if (!value) return "";
  if (value.length <= tail) return "•".repeat(value.length);
  return "•".repeat(Math.min(8, value.length - tail)) + value.slice(-tail);
}

export function MaskedValue({
  value, tail = 4, canReveal = false, onReveal,
}: {
  value: string | null | undefined;
  tail?: number;
  canReveal?: boolean;
  onReveal?: () => void;
}) {
  const { t } = useI18n();
  const [shown, setShown] = React.useState(false);
  if (!value) return <span className="text-muted-foreground">—</span>;
  return (
    <span className="inline-flex items-center gap-2 tabular-nums">
      <span>{shown ? value : maskValue(value, tail)}</span>
      {canReveal && (
        <button
          type="button"
          className="txt-caption text-primary-ink hover:underline focus-ring rounded-field"
          onClick={() => { if (!shown) onReveal?.(); setShown((s) => !s); }}
        >
          {shown ? t("actionKit.hide") : t("actionKit.reveal")}
        </button>
      )}
    </span>
  );
}
