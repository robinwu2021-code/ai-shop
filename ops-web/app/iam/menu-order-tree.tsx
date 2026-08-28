"use client";

// 菜单顺序的两层列表：拖动排序 + ↑/↓。
//
// **为什么不复用 components/ui/tree.tsx**：那个组件同时服务于角色抽屉的勾选树，
// 那里拖动没有意义。把拖动塞进去，等于让一个被两处使用的组件长出一半用不到的状态
// （P1 的顺序是「复用 → 扩展 → 才新建」，而这里扩展会伤到另一个调用方）。
//
// **为什么用原生 HTML5 拖拽而不引 @dnd-kit**：这个仓库已经表过态 ——
// globals.css 里为了三个动画类手写了 keyframes，注释写着
// 「补上真实实现，而不是加一个依赖」。后台的同级列表排序，原生 API 够用。
// 代价写明：**不支持触屏拖动**，所以 ↑/↓ 全程保留，那是键盘与触屏的可用路径。
import * as React from "react";
import { ChevronDown, ChevronUp, GripVertical } from "lucide-react";
import { canDrop, reorderWithin, type DragItem } from "@/lib/reorder";
import { cn } from "@/lib/utils";

/** 顶层的父级 key。**用固定串而不是 undefined** —— 两个 undefined 会相等 */
const ROOT = "__root";

export interface OrderNode {
  key: string;
  name: string;
  /** 行尾的弱化说明（二级分组名） */
  hint?: string | null;
  children?: OrderNode[];
}

export function MenuOrderTree({
  nodes, canEdit, busy, onReorder, onMove, labels,
}: {
  nodes: OrderNode[];
  canEdit: boolean;
  busy: boolean;
  /** 同级重排：parentKey 为 ROOT 表示顶层 */
  onReorder: (parentKey: string, orderedKeys: string[]) => void;
  onMove: (parentKey: string, key: string, dir: "UP" | "DOWN") => void;
  labels: { moveUp: string; moveDown: string; dragHint: string; empty: string };
}) {
  /*
   * 拖动中的那一项存**两份**：ref 供判定，state 供渲染。
   *
   * 只用 state 的话，判定就依赖「dragstart 与 dragover 之间发生过一次渲染」——
   * 真实拖动有帧间隔所以看着没问题，但那是巧合不是保证：
   * 事件连着来时（合成事件、快速拖拽、自动化）闭包里还是旧值，判定直接失效。
   * ref 是同步的，不欠这个债。
   */
  const draggingRef = React.useRef<DragItem | null>(null);
  const [dragging, setDragging] = React.useState<DragItem | null>(null);
  /** 落点提示：画在哪一行的上边缘。**一条 2px 横线**，不整行高亮 —— */
  /*  高亮说不清是「放在它上面」还是「替换它」，而横线只有一个意思 */
  const [dropOn, setDropOn] = React.useState<string | null>(null);

  const clear = () => { draggingRef.current = null; setDragging(null); setDropOn(null); };

  const rows = (list: OrderNode[], parentKey: string, depth: number): React.ReactNode =>
    list.map((n, i) => {
      const me: DragItem = { key: n.key, parentKey };
      const active = dragging?.key === n.key;
      return (
        <div key={n.key}>
          <div
            draggable={canEdit && !busy}
            onDragStart={(e) => {
              draggingRef.current = me;
              setDragging(me);
              // 不设 dataTransfer 的话 Firefox 不会启动拖拽
              e.dataTransfer.effectAllowed = "move";
              e.dataTransfer.setData("text/plain", n.key);
            }}
            onDragEnd={clear}
            onDragOver={(e) => {
              if (!canDrop(draggingRef.current, me)) return;   // 跨父级：不 preventDefault = 不可放下
              e.preventDefault();
              e.dataTransfer.dropEffect = "move";
              setDropOn(n.key);
            }}
            onDragLeave={() => setDropOn((k) => (k === n.key ? null : k))}
            onDrop={(e) => {
              e.preventDefault();
              const from = draggingRef.current;
              if (!canDrop(from, me)) return clear();
              const keys = list.map((x) => x.key);
              const next = reorderWithin(keys, keys.indexOf(from!.key), i);
              // reorderWithin 没变化时返回原数组本身 —— 用 !== 判断，省掉一次请求
              if (next !== keys) onReorder(parentKey, [...next]);
              clear();
            }}
            className={cn(
              "group flex items-center gap-2 rounded-field px-2 py-1.5 txt-body",
              // 落点线画在上边缘：拖到哪一行，就插在那一行之前
              dropOn === n.key && "border-t-2 border-[var(--primary)]",
              active && "opacity-40",
              canEdit && !busy && "cursor-grab active:cursor-grabbing hover:bg-accent/50",
            )}
            style={{ paddingInlineStart: 8 + depth * 20 }}
          >
            {canEdit && (
              <GripVertical
                className="size-3.5 shrink-0 text-muted-foreground/50 group-hover:text-muted-foreground"
                aria-hidden
              />
            )}
            <span className={cn("truncate", depth === 0 && "txt-strong")}>{n.name}</span>
            {n.hint && <span className="truncate txt-caption text-muted-foreground">{n.hint}</span>}

            {canEdit && (
              <span className="ms-auto flex shrink-0 items-center gap-0.5" title={labels.dragHint}>
                {(["UP", "DOWN"] as const).map((dir) => (
                  <button
                    key={dir}
                    type="button"
                    aria-label={dir === "UP" ? labels.moveUp : labels.moveDown}
                    title={dir === "UP" ? labels.moveUp : labels.moveDown}
                    // 拖动进行中禁用：两条路径同时改会互相覆盖
                    disabled={busy || !!dragging}
                    onClick={() => onMove(parentKey, n.key, dir)}
                    className="focus-ring rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-40"
                  >
                    {dir === "UP" ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
                  </button>
                ))}
              </span>
            )}
          </div>
          {n.children?.length ? rows(n.children, n.key, depth + 1) : null}
        </div>
      );
    });

  if (!nodes.length) {
    return <div className="py-8 text-center txt-body text-muted-foreground">{labels.empty}</div>;
  }
  return <div className="rounded-card border border-card-border bg-card p-2">{rows(nodes, ROOT, 0)}</div>;
}

export { ROOT as ORDER_ROOT };
