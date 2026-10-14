/**
 * 列表内重排的纯逻辑。**与拖拽 API 无关**，因此可单测 ——
 * 而拖放的边界（拖到自己身上、拖到末尾之后、跨父级）恰恰是最容易写错的部分，
 * 在浏览器里逐个试一遍要很久，还试不全。
 */

/**
 * 把 `from` 位置的元素移动到 `to` 位置（**插入语义**，不是交换）。
 *
 * <p>插入而不是交换：拖动的心智是「把它放到这两行之间」，
 * 交换会让中间那些行也跟着乱动 —— 用户看到的结果与他拖的动作对不上。
 *
 * `from === to` 或越界时返回**原数组本身**（不是副本），
 * 让调用方能用 `===` 判断「什么都没变」，从而跳过一次请求。
 */
export function reorderWithin<T>(list: readonly T[], from: number, to: number): readonly T[] {
  if (from === to || from < 0 || from >= list.length || to < 0 || to >= list.length) {
    return list;
  }
  const out = [...list];
  const [moved] = out.splice(from, 1);
  out.splice(to, 0, moved);
  return out;
}

/** 拖动中的那一项：靠 parentKey 判定能不能落在目标处。 */
export interface DragItem {
  key: string;
  /** 同一父级才允许互相落。顶层用一个固定串，别用 undefined —— 两个 undefined 会相等 */
  parentKey: string;
}

/**
 * 这一次拖放合不合法。
 *
 * <p><b>只允许同父级</b>：把 A 分区的菜单项拖进 B 分区是**改菜单结构**
 * （换了 function_code），不是排序 —— 那要走 nav.ts → 生成器 → 迁移那条链路，
 * 在配置页上点两下就改掉的话，界面与代码里的菜单树会当场分叉。
 *
 * <p>落在自己身上也算不合法：它不产生任何变化，但会白发一次请求。
 */
export function canDrop(dragging: DragItem | null, target: DragItem): boolean {
  return !!dragging && dragging.parentKey === target.parentKey && dragging.key !== target.key;
}
