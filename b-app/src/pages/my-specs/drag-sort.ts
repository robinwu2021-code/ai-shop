// 「商品规格」页的**拖动排序**：规格行（竖着一列）与档位（横着换行排的 chip）两套。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// 这两套此前在 `index.vue` 里各写一遍，共 250 行，**规矩相同、几何不同**：
// 同样的长按认定、同样的 slop 放弃、同样的「拖动中不动数组」、同样的落位闪一下；
// 差别只在「手指现在指向第几个」怎么算（行高整除 vs 离哪个 chip 最近）。
//
// <p>写成两份的代价不是行数，是**改一处漏一处**：HOLD_MS 与 SLOP 这两个数
// 当初是一起调出来的，分散在两处就迟早分叉，而分叉了没有任何报错 ——
// 只是两个地方手感不一样，谁也说不清哪个才是对的。
//
// <p>搬过来的实现一个字没改，只把「几何」与「落位之后做什么」变成入参。
import { ref } from "vue";
import type { ComponentInternalInstance } from "vue";

/** 长按多久算「他要拖」。太短会把点击吞掉，太长会让人以为没反应 */
export const HOLD_MS = 180;
/** 认定之前手指移动超过这么多 px 就当他在滚页面，放弃这次拖动 */
export const SLOP = 10;

/**
 * 把 from 挪到 to，**越界就原样返回**。
 *
 * 上一版少的就是这个判断：`arr.splice(越界, 1)` 返回 `[]`，
 * 取 `[0]` 得到 undefined，再插回数组 —— 于是渲染整个塌掉。
 */
export function moveItem<T>(arr: T[], from: number, to: number): T[] {
  if (from < 0 || from >= arr.length) return arr;
  const next = [...arr];
  const item = next.splice(from, 1)[0];
  if (item === undefined) return arr;
  next.splice(Math.max(0, Math.min(next.length, to)), 0, item);
  return next;
}

/**
 * 档位那一排（chip，会换行，所以是二维）。
 *
 * @param instance 当前组件实例 —— `createSelectorQuery().in()` 要它
 * @param selector 量位置用的选择器，如 `".vals .val"`
 * @param count    当前有几个（量位置时按它截断）
 * @param onDrop   松手且真的挪了位：`(from, to)`
 */
export function useChipDrag(
  instance: ComponentInternalInstance | null,
  selector: string,
  count: () => number,
  onDrop: (from: number, to: number) => void,
) {
  /** 正在拖的档位下标；-1 = 没在拖 */
  const dragFrom = ref(-1);
  /** 手指按着但还没到 HOLD_MS —— 这个阶段什么都不做，抬手就是一次普通点击 */
  const pending = ref(-1);
  /** 落点：拖动中只用来画插入位，不动数组 */
  const dragTo = ref(-1);
  /** 被拖的 chip 相对起点的位移，直接喂给 transform */
  const shift = ref({ x: 0, y: 0 });
  const origin = ref({ x: 0, y: 0 });
  let timer: ReturnType<typeof setTimeout> | null = null;
  /** 每个 chip 的中心点，按下时量一次 */
  const boxes = ref<{ x: number; y: number }[]>([]);

  function clearTimer() {
    if (timer) { clearTimeout(timer); timer = null; }
  }

  function onStart(i: number, e: TouchEvent) {
    const t = e.touches?.[0];
    if (!t) return;
    pending.value = i;
    origin.value = { x: t.clientX, y: t.clientY };
    shift.value = { x: 0, y: 0 };
    boxes.value = [];
    /*
     * **用 uni 的 createSelectorQuery 量位置，不从事件对象拿 DOM。**
     * uni 把事件包装过：`currentTarget` 在 H5 上不是 HTMLElement，
     * 在小程序上更没有 getBoundingClientRect —— 照 DOM 那样写，
     * 表现是「按下去什么都不发生」，而不会报错，很难看出原因。
     */
    uni.createSelectorQuery()
      .in(instance)
      .selectAll(selector)
      .boundingClientRect((res) => {
        const rects = (Array.isArray(res) ? res : [res]) as UniApp.NodeInfo[];
        boxes.value = rects
          .slice(0, count())
          .map((r) => ({
            x: (r.left ?? 0) + (r.width ?? 0) / 2,
            y: (r.top ?? 0) + (r.height ?? 0) / 2,
          }));
      })
      .exec();
    clearTimer();
    timer = setTimeout(() => {
      // 手指还在原地按着 → 这是一次拖动。震一下告诉他「拿起来了」
      if (pending.value !== i) return;
      dragFrom.value = i;
      dragTo.value = i;
      uni.vibrateShort?.({ success: () => {}, fail: () => {} });
    }, HOLD_MS);
  }

  function onMove(e: TouchEvent) {
    const t = e.touches?.[0];
    if (!t) return;
    if (dragFrom.value < 0) {
      // 还没认定：动得太多就是在滚页面，放弃（否则点 ✕ 时手抖也会变成拖动）
      const dx = t.clientX - origin.value.x;
      const dy = t.clientY - origin.value.y;
      if (dx * dx + dy * dy > SLOP * SLOP) { clearTimer(); pending.value = -1; }
      return;
    }
    shift.value = { x: t.clientX - origin.value.x, y: t.clientY - origin.value.y };
    if (!boxes.value.length) return;
    // 离手指最近的那个 chip 就是落点。**只记下来，不动数组**
    let best = dragFrom.value;
    let bestD = Infinity;
    boxes.value.forEach((b, i) => {
      const d = (b.x - t.clientX) ** 2 + (b.y - t.clientY) ** 2;
      if (d < bestD) { bestD = d; best = i; }
    });
    dragTo.value = best;
  }

  function onEnd() {
    clearTimer();
    const from = dragFrom.value;
    const to = dragTo.value;
    pending.value = -1;
    dragFrom.value = -1;
    dragTo.value = -1;
    shift.value = { x: 0, y: 0 };
    boxes.value = [];
    if (from < 0 || to < 0 || from === to) return;
    onDrop(from, to);
  }

  /**
   * 取消可能正在计时的那次长按。
   *
   * <p>删一档的时候要调：不取消的话，删完手指还没抬起，计时器照样把「拖动」
   * 点着，而它记的下标已经指不到东西了。
   */
  function cancel() {
    clearTimer();
    pending.value = -1;
    dragFrom.value = -1;
  }

  return { dragFrom, pending, dragTo, shift, onStart, onMove, onEnd, cancel };
}

/**
 * 规格行那一列（整行，只有上下，所以是一维）。
 *
 * @param instance    当前组件实例
 * @param rowSelector 量行高用的选择器，如 `".spec"`
 * @param indexOf     这一行现在排第几（键是行的稳定 id）
 * @param count       这一栏共几行（落点要夹在里面）
 * @param onDrop      松手：`(fromKey, to)`。真的挪没挪由调用方判断 ——
 *                    它要拿 from 的键去找是哪一栏的数据
 */
export function useRowDrag(
  instance: ComponentInternalInstance | null,
  rowSelector: string,
  indexOf: (key: string) => number,
  count: () => number,
  onDrop: (fromKey: string, to: number) => void | Promise<void>,
) {
  const dragFrom = ref<string | null>(null);
  /** 手指按着但还没到 HOLD_MS。这个阶段抬手 = 一次普通点击，不会重排 */
  const pending = ref<string | null>(null);
  const originY = ref(0);
  const shift = ref(0);
  const dragTo = ref(-1);
  /** 一行的高度（px）。按下时量一次 —— 不同机型、不同字号下它不一样 */
  const rowH = ref(64);
  let timer: ReturnType<typeof setTimeout> | null = null;

  function clearTimer() {
    if (timer) { clearTimeout(timer); timer = null; }
  }

  function onStart(key: string, e: TouchEvent) {
    const t = e.touches?.[0];
    if (!t) return;
    pending.value = key;
    originY.value = t.clientY;
    shift.value = 0;
    dragTo.value = indexOf(key);
    // 行高按下时量一次：档位多的行更高，写死的话拖两行就错位
    uni.createSelectorQuery().in(instance).select(rowSelector)
      .boundingClientRect((r) => {
        const h = (r as UniApp.NodeInfo | null)?.height;
        if (h) rowH.value = h;
      })
      .exec();
    clearTimer();
    timer = setTimeout(() => {
      if (pending.value !== key) return;
      dragFrom.value = key;
      uni.vibrateShort?.({ success: () => {}, fail: () => {} });
    }, HOLD_MS);
  }

  function onMove(e: TouchEvent) {
    const t = e.touches?.[0];
    if (!t) return;
    if (!dragFrom.value) {
      // 还没认定就动了这么多 —— 他在滚页面，不是在拖这一行
      if (Math.abs(t.clientY - originY.value) > SLOP) { clearTimer(); pending.value = null; }
      return;
    }
    shift.value = t.clientY - originY.value;
    const from = indexOf(dragFrom.value);
    const delta = Math.round(shift.value / (rowH.value || 64));
    dragTo.value = Math.max(0, Math.min(count() - 1, from + delta));
  }

  async function onEnd() {
    clearTimer();
    const from = dragFrom.value;
    const to = dragTo.value;
    pending.value = null;
    dragFrom.value = null;
    shift.value = 0;
    dragTo.value = -1;
    if (!from || to < 0) return;
    await onDrop(from, to);
  }

  return { dragFrom, pending, dragTo, shift, rowH, onStart, onMove, onEnd };
}
