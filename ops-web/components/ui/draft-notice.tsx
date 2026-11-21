"use client";

// 抽屉顶部的草稿提示：恢复了草稿（可放弃）/ 草稿基于旧版本（冲突）/ 浏览器存不了草稿。
import * as React from "react";
import { Notice } from "./notice";
import { Button } from "./button";
import { useI18n } from "@/lib/i18n";
import { fmtTime } from "@/lib/utils";

export function DraftNotice({
  restored, conflict, persistFailed, onDiscard,
}: {
  restored: { savedAt: number } | null;
  conflict?: boolean;
  persistFailed?: boolean;
  onDiscard: () => void;
}) {
  const { t } = useI18n();
  if (persistFailed) return <Notice tone="warning" className="mb-4">{t("actionKit.draftUnavailable")}</Notice>;
  if (!restored) return null;
  return (
    <Notice tone={conflict ? "warning" : "info"} className="mb-4">
      <div className="flex flex-wrap items-center gap-2">
        <span>{conflict ? t("actionKit.draftConflict") : t("actionKit.draftRestored", { time: fmtTime(restored.savedAt) })}</span>
        <Button size="sm" variant="ghost" className="ms-auto" onClick={onDiscard}>{t("actionKit.draftDiscard")}</Button>
      </div>
    </Notice>
  );
}
