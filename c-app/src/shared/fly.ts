// 「飞入购物车」动效的共享状态。
// 用模块级 reactive 而不是 store：这是纯展示态，不需要持久化，也不该进 Pinia devtools。
import { reactive } from "vue";

export const flyState = reactive({
  visible: false,
  flying: false,
  emoji: "🛒",
  /** 起点（px，视口坐标） */
  x: 0,
  y: 0,
  /** 相对起点的位移（px） */
  dx: 0,
  dy: 0,
  /** 落点处的角标弹跳（购物车图标自己监听这个计数器） */
  landTick: 0,
});

/**
 * 购物车图标的视口坐标。
 * 不写死位置 —— tab 页的落点在底部菜单里，详情页的落点在操作条的购物车入口上，
 * 两处都会在挂载时把自己的实际位置报上来（sh-tabbar / 详情页操作条）。
 * 没人上报时（理论上不该发生）回落到屏幕右下角。
 */
const anchor = { x: 0, y: 0, set: false };

export function setCartAnchor(x: number, y: number): void {
  anchor.x = x;
  anchor.y = y;
  anchor.set = true;
}

export function clearCartAnchor(): void {
  anchor.set = false;
}

let hideTimer: ReturnType<typeof setTimeout> | undefined;
let landTimer: ReturnType<typeof setTimeout> | undefined;

/** 动效总时长（ms），与 sh-fly-cart 的 transition 时长保持一致 */
const FLY_MS = 620;

// 开发期调试钩子：在控制台里可直接观察动效状态机
if (import.meta.env.DEV) {
  (globalThis as unknown as { __flyState?: typeof flyState }).__flyState = flyState;
}

/**
 * 触发一次飞入动画。
 * @param x,y   起点视口坐标（取自 tap 事件）
 * @param emoji 飞行小球里显示的内容（用商品图占位符，视觉上更连贯）
 */
export function flyToCart(x: number, y: number, emoji = "🛒"): void {
  let targetX = anchor.x;
  let targetY = anchor.y;
  if (!anchor.set) {
    const { windowWidth, windowHeight } = uni.getSystemInfoSync();
    targetX = windowWidth - 60;
    targetY = windowHeight - 60;
  }

  clearTimeout(hideTimer);
  clearTimeout(landTimer);
  flyState.emoji = emoji;
  flyState.x = x;
  flyState.y = y;
  flyState.dx = 0;
  flyState.dy = 0;
  flyState.visible = true;
  flyState.flying = false;

  // 下一帧再置 flying，否则起始态与终止态在同一帧提交，transition 不会触发
  setTimeout(() => {
    flyState.dx = targetX - x;
    flyState.dy = targetY - y;
    flyState.flying = true;
  }, 20);

  // 小球落到购物车的瞬间，让图标弹一下 —— 动效的终点要有回应，否则「飞过去了然后呢」
  landTimer = setTimeout(() => {
    flyState.landTick += 1;
  }, FLY_MS);

  hideTimer = setTimeout(() => {
    flyState.visible = false;
    flyState.flying = false;
  }, FLY_MS + 40);
}

interface TapLike {
  touches?: { clientX: number; clientY: number }[];
  changedTouches?: { clientX: number; clientY: number }[];
  detail?: { x?: number; y?: number };
  clientX?: number;
  clientY?: number;
}

/**
 * 从 tap 事件里取视口坐标。
 * 各端事件形状不同：小程序给 touches/changedTouches，H5 给 clientX/Y，
 * uni 合成事件有时只给 detail.x/y。事件缺失时回落到屏幕中心 —— 动效可以退化，不能抛错。
 */
export function tapPoint(e?: TapLike | null): { x: number; y: number } {
  const t = e?.touches?.[0] ?? e?.changedTouches?.[0];
  if (t) return { x: t.clientX, y: t.clientY };
  if (typeof e?.clientX === "number" && typeof e?.clientY === "number") {
    return { x: e.clientX, y: e.clientY };
  }
  if (typeof e?.detail?.x === "number" && typeof e.detail.y === "number") {
    return { x: e.detail.x, y: e.detail.y };
  }
  const { windowWidth, windowHeight } = uni.getSystemInfoSync();
  return { x: windowWidth / 2, y: windowHeight / 3 };
}

/**
 * 量出某个元素的中心点并登记为购物车落点。
 * 用 createSelectorQuery 而非 getBoundingClientRect —— 后者小程序里没有。
 *
 * ⚠️ 坐标系要对齐：createSelectorQuery 返回的是**页面坐标**，而飞行层是 position: fixed 的
 * **视口坐标**。H5 端 uni 把导航栏画在 DOM 里，两者差一个导航栏高度（windowTop），
 * 不补这一段，小球会稳定地落在购物车正上方 44px 处 —— 看着「差一点点」，很难归因。
 * 小程序端导航栏是原生的、不在 webview 内，windowTop 为 0，这里的补偿自然为零。
 */
export function registerCartAnchor(selector: string, ctx?: unknown): void {
  const query = uni.createSelectorQuery();
  // #ifndef H5
  if (ctx) query.in(ctx as never);
  // #endif
  query
    .select(selector)
    .boundingClientRect((rect) => {
      const r = rect as UniApp.NodeInfo | null;
      if (r && typeof r.left === "number" && typeof r.top === "number") {
        const windowTop = uni.getSystemInfoSync().windowTop ?? 0;
        setCartAnchor(
          r.left + (r.width ?? 0) / 2,
          r.top + (r.height ?? 0) / 2 + windowTop,
        );
      }
    })
    .exec();
}
