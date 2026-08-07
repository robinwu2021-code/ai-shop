"use client";

// 树形列表（原语）：给「层级本身就是信息」的列表用 —— 组织架构、权限码目录。
//
// 存在的唯一理由：扁平表格加一列「上级」时，读者得一行一行用眼睛把父子关系拼回来；
// 三层以上基本拼不出来。缩进 + 展开 + 竖线在这里定死一份，调用方只给数据。
//
// 两种形态共用同一份缩进/展开逻辑：
//   - 只读树（组织架构）：extra 放成员数、负责人、「编辑」按钮
//   - 勾选树（角色功能权限）：checkable=true，父节点三态；**值只认叶子 key**，
//     父节点不入选中集合 —— 否则「父勾了但后来新增了子」会留下语义不明的半真状态。
import * as React from "react";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { Checkbox } from "./checkbox";
import { Skeleton, EmptyState } from "./misc";

export interface TreeNode {
  key: string;
  label: React.ReactNode;
  /** 行右侧：统计数字、徽标、操作按钮 */
  extra?: React.ReactNode;
  children?: TreeNode[];
}

/** 子孙叶子的 key。父节点的勾选态、以及点父节点时要改哪些值，都由它算出来。 */
export function leafKeysOf(n: TreeNode): string[] {
  if (!n.children?.length) return [n.key];
  return n.children.flatMap(leafKeysOf);
}

/** 三态 checkbox。项目无 checkbox 原语（DataTable 里那个未导出），这里就地实现，保持扁平无描边风格。 */

function Row({
  node, depth, checkable, checked, disabled, onCheck, collapseFrom, open, setOpen,
}: {
  node: TreeNode;
  depth: number;
  checkable?: boolean;
  checked: Set<string>;
  disabled?: boolean;
  onCheck?: (leaves: string[], next: boolean) => void;
  collapseFrom?: number;
  open: Record<string, boolean>;
  setOpen: React.Dispatch<React.SetStateAction<Record<string, boolean>>>;
}) {
  const { t } = useI18n();
  const kids = node.children ?? [];
  const hasKids = kids.length > 0;
  // 未被用户手动切过的节点按 collapseFrom 决定初始展开：undefined = 全展开
  const expanded = open[node.key] ?? depth < (collapseFrom ?? Number.MAX_SAFE_INTEGER);
  const leaves = React.useMemo(() => leafKeysOf(node), [node]);
  const on = leaves.filter((k) => checked.has(k)).length;
  const state = on === 0 ? "off" : on === leaves.length ? "on" : "partial";

  return (
    <li>
      <div
        className="flex items-center gap-2 rounded-field py-1.5 pe-2 hover:bg-accent/60"
        style={{ paddingInlineStart: `${depth * 20}px` }}
      >
        {hasKids ? (
          <button
            type="button"
            aria-expanded={expanded}
            aria-label={expanded ? t("table.collapse") : t("table.expand")}
            className="focus-ring rounded-field p-0.5 text-muted-foreground hover:bg-accent"
            onClick={() => setOpen((o) => ({ ...o, [node.key]: !expanded }))}
          >
            <ChevronRight className={cn("size-4 transition-transform", expanded && "rotate-90")} />
          </button>
        ) : (
          <span className="size-5 shrink-0" />
        )}
        {/* 复用 Checkbox 原语。此前这里是第二份就地实现的裸 input[type=checkbox]，
            与表格里的那份在半选写法、焦点环、圆角上各不相同。 */}
        {checkable && (
          <Checkbox
            checked={state === "on" ? true : state === "partial" ? "indeterminate" : false}
            disabled={disabled}
            aria-label={typeof node.label === "string" ? node.label : node.key}
            onChange={() => onCheck?.(leaves, state !== "on")}
          />
        )}
        <div className="min-w-0 flex-1 text-sm">{node.label}</div>
        {node.extra != null && <div className="flex shrink-0 items-center gap-1.5">{node.extra}</div>}
      </div>
      {hasKids && expanded && (
        <ul>
          {kids.map((c) => (
            <Row
              key={c.key} node={c} depth={depth + 1} checkable={checkable} checked={checked}
              disabled={disabled} onCheck={onCheck} collapseFrom={collapseFrom} open={open} setOpen={setOpen}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

export function Tree({
  nodes, empty, checkable, checkedKeys, onCheckedChange, disabled, collapseFrom, loading, loadingText,
}: {
  nodes: TreeNode[];
  /** 空树文案（与 DataTable 的 empty 同义：要写清「为什么空」）*/
  empty: string;
  checkable?: boolean;
  /** 已勾选的**叶子** key */
  checkedKeys?: string[];
  /** 回调给完整的叶子 key 列表（受控，勾选态由调用方持有） */
  onCheckedChange?: (keys: string[]) => void;
  disabled?: boolean;
  /** 深度 >= 该值的节点默认收起；不传 = 全展开。用户手动切过的节点不受影响。 */
  collapseFrom?: number;
  loading?: boolean;
  loadingText?: string;
}) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState<Record<string, boolean>>({});
  const checked = React.useMemo(() => new Set(checkedKeys ?? []), [checkedKeys]);

  const onCheck = (leaves: string[], next: boolean) => {
    const s = new Set(checked);
    for (const k of leaves) { if (next) s.add(k); else s.delete(k); }
    onCheckedChange?.([...s]);
  };

  // 加载态与 DataTable 对齐用骨架：此前同一个页面里「加载中」有三种长相
  // （表格是骨架块、树是一行灰字、时间线又是另一行灰字）
  if (loading) {
    return (
      <div className="space-y-2" aria-label={loadingText ?? t("common.loading")} aria-busy>
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} className="h-[var(--row-h)]" style={{ width: `${[70, 55, 62, 48, 58][i]}%` }} />
        ))}
      </div>
    );
  }
  if (!nodes.length) return <EmptyState title={empty} />;
  return (
    <ul role="tree">
      {nodes.map((n) => (
        <Row
          key={n.key} node={n} depth={0} checkable={checkable} checked={checked}
          disabled={disabled} onCheck={onCheck} collapseFrom={collapseFrom} open={open} setOpen={setOpen}
        />
      ))}
    </ul>
  );
}
