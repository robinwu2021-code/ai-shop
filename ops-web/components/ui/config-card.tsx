"use client";

// 配置卡片（组合件）：标题 + 说明 + 表单内容 + 保存按钮 + 「上次修改」页脚。
//
// **为什么需要它**：8 个页面都在手拼「保存按钮 + 上次修改：{时间} · {操作人}」，
// 措辞与排版各写各的。这类页脚看着不起眼，但它是运营排查"这个配置什么时候被谁改的"
// 的唯一线索 —— 应该长得一样、都在同一个位置。
import * as React from "react";
import { Button } from "./button";
import { Card, CardContent, CardHeader, CardTitle } from "./card";
import { Notice } from "./notice";
import { fmtTime } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";

export function ConfigCard({
  title, notice, readOnly, children, onSave, saveLabel, saving, canSave = true, updatedAt, updatedBy, className,
}: {
  title: string;
  /** 说明条：写清「改了之后会发生什么」，不是重复标题 */
  notice?: React.ReactNode;
  /**
   * 无权限提示（`<ReadOnlyNotice/>`）。放在卡片**内部、说明条之前** ——
   * 摆到卡片外面就会横跨整个页宽，看着像页面级公告，而它说的只是这一张卡片能不能改。
   */
  readOnly?: React.ReactNode;
  children: React.ReactNode;
  onSave?: () => void;
  /** 按钮文案。默认「保存」；下发到 C 端这类**立刻对外生效**的动作用「下发」，别让人以为只是存草稿 */
  saveLabel?: string;
  saving?: boolean;
  /** 无权限时置 false：按钮禁用而不是隐藏（页面另有 ReadOnlyNotice 说明缺什么权限） */
  canSave?: boolean;
  updatedAt?: string;
  updatedBy?: string;
  className?: string;
}) {
  const { t } = useI18n();
  return (
    <Card className={cn("max-w-2xl", className)}>
      <CardHeader><CardTitle>{title}</CardTitle></CardHeader>
      <CardContent>
        {readOnly}
        {notice && <Notice className="mb-4">{notice}</Notice>}
        <div className="space-y-4">{children}</div>
        {onSave && (
          <div className="mt-5 flex items-center gap-3">
            <Button disabled={!canSave} loading={saving} onClick={onSave}>{saveLabel ?? t("common.save")}</Button>
            {/* 运营排查「谁什么时候改的」就靠这一行，所以它必须在每个配置页都长一样 */}
            {(updatedAt || updatedBy) && (
              <span className="txt-caption text-muted-foreground">
                {updatedBy
                  ? t("card.updatedByWho", { time: fmtTime(updatedAt), who: updatedBy })
                  : t("card.updatedBy", { time: fmtTime(updatedAt) })}
              </span>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
