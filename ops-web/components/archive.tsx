"use client";

// G1 软删除的页面侧统一件（TDD §10.1）。
//
// 归档要铺到 15 个主数据页。**不把这几件收敛掉，15 个页面就是 15 种归档写法**——
// 有的写"删除"有的写"归档"、已归档行有的灰有的不灰、确认弹窗文案各说各话。
// 放在 components/ 根而不是 components/ui/：ui/ 是 B0 定稿的通用原语，这里是业务约定层。
import * as React from "react";
import { Archive, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { fmtTime } from "@/lib/utils";
import { useI18n, translate } from "@/lib/i18n";
import { useLocaleStore } from "@/lib/stores/locale";

/** 这几个 confirm 是**纯函数**（供 `confirm({...})` 直接吃），拿不到 hook，只能读 store 快照。 */
const currentLocale = () => useLocaleStore.getState().locale;

// 归档动作的统一文案（按钮叫「归档」不叫「删除」—— 叫删除会让人以为数据没了）。
// 文案走 i18n：这几个词出现在 6 个页面的操作列与确认弹窗里。
export const ARCHIVE_LABEL_KEY = "archive.label";
export const UNARCHIVE_LABEL_KEY = "archive.unlabel";

/**
 * Toolbar 筛选槽里的「显示已归档」开关。
 * 刻意做成带边框的小胶囊而非裸 checkbox：它和旁边的 Select 同处一行，视觉重量要对齐。
 */
export function ShowArchivedToggle({
  checked, onChange, label,
}: { checked: boolean; onChange: (v: boolean) => void; label?: string }) {
  const { t } = useI18n();
  const text = label ?? t("archive.showArchived");
  return (
    <label // 与工具栏里的 Input / FilterSelect 并排，高度必须同源（--ctl-h），圆角走控件档
    className="inline-flex h-[var(--ctl-h)] cursor-pointer select-none items-center gap-2 rounded-field bg-secondary px-3 txt-body">
      {/* 用 Checkbox 原语：裸 input 没有焦点环、圆角也不走档 —— 这是全站第三份就地实现，
          Tree 与 DataTable 的两份已经收掉了。 */}
      <Checkbox checked={checked} onChange={(v) => onChange(v === true)} />
      {text}
    </label>
  );
}

// 归档开关也是一种筛选：开着它列表里会混进已归档的行，
// 忘了关会当成"这条怎么还在"。所以它同样要出现在筛选回显里。
ShowArchivedToggle.toChip = (p: { checked: boolean; onChange: (v: boolean) => void; label?: string }) =>
  p.checked ? { label: p.label ?? translate(currentLocale(), "archive.showArchived"), clear: () => p.onChange(false) } : null;

/** 已归档行整行弱化。传给 `DataTable` 的 `rowClassName`。 */
export const archivedRowClass = (row: { archivedAt?: string | null }) =>
  row.archivedAt ? "opacity-60" : undefined;

/** 尾列的归档时间（未归档显示 `-`，不留空白让人以为是渲染坏了）。 */
export function ArchivedAt({ at }: { at?: string | null }) {
  return <span className="text-muted-foreground">{at ? fmtTime(at) : "-"}</span>;
}

/**
 * 操作列的归档 / 恢复按钮。
 *
 * **已归档行只出「恢复」，其余动作按钮一律不出**——这是本组件存在的主要理由：
 * 让"已归档行还能编辑/下发指令"这类错误在调用点就不可能写出来（`actions` 只在未归档时渲染）。
 */
export function ArchiveActions({
  archived, onArchive, onUnarchive, canWrite = true, canArchive = true, archiveHint, actions,
}: {
  archived: boolean;
  onArchive: () => void;
  onUnarchive: () => void;
  /** 无写权限时不出动作按钮（页面另有「仅可查看」提示，不做静默隐藏） */
  canWrite?: boolean;
  /**
   * 该行是否**允许归档**（区别于 canWrite 的"整体有无写权限"）。
   * 用于内置角色、系统预置数据这类"有写权限但这一行不能归档"的场景——
   * 否则页面只能绕过本组件自己拼一套按钮，样式就此发散。
   */
  canArchive?: boolean;
  /** canArchive=false 时的悬浮说明（如「内置角色不可归档」） */
  archiveHint?: string;
  /** 未归档时才显示的其它动作（编辑、指令…） */
  actions?: React.ReactNode;
}) {
  const { t } = useI18n();
  if (!canWrite) return <span className="text-muted-foreground">-</span>;
  if (archived) {
    return (
      <Button size="sm" variant="outline" onClick={onUnarchive}>
        <RotateCcw className="size-4" /> {t(UNARCHIVE_LABEL_KEY)}
      </Button>
    );
  }
  return (
    // flex-nowrap：操作列一换行整行就变两倍高。**行内动作 ≤2 个**，
    // 更多请收进 RowActions（见 components/README.md 用法约定）。
    <div className="flex flex-nowrap items-center gap-2">
      {actions}
      <Button
        disabled={!canArchive}
        title={canArchive ? undefined : archiveHint} size="sm" variant="outline" onClick={onArchive}>
        <Archive className="size-4" /> {t(ARCHIVE_LABEL_KEY)}
      </Button>
    </div>
  );
}

/**
 * 归档二次确认的统一配置，直接喂 `useConfirm().confirm(...)`。
 *
 * @param entity 实体名，如「商家」「自提点」
 * @param name   这一条的可读标识（编号或名称），进标题让人确认删对了行
 * @param requireText 主数据类（商家/社区/自提点/角色）传编号，要求手输确认
 */
export const archiveConfirm = (entity: string, name: string, requireText?: string, action?: () => Promise<unknown>) => ({
  title: translate(currentLocale(), "archive.confirmTitle", { entity, name }),
  desc: translate(currentLocale(), requireText ? "archive.confirmDescMaster" : "archive.confirmDesc", { entity }),
  danger: true,
  confirmText: translate(currentLocale(), ARCHIVE_LABEL_KEY),
  requireText,
  // 交给弹窗自己跑完：归档是主数据上不该连点两次的动作，
  // 弹窗会在期间禁用按钮并转圈，失败时留在原地
  action,
});

/** 恢复的二次确认（低危，不要求输入文本）。 */
export const unarchiveConfirm = (entity: string, name: string, action?: () => Promise<unknown>) => ({
  title: translate(currentLocale(), "archive.unconfirmTitle", { entity, name }),
  desc: translate(currentLocale(), "archive.unconfirmDesc", { entity }),
  confirmText: translate(currentLocale(), UNARCHIVE_LABEL_KEY),
  action,
});
