/**
 * 这份产物连的是真后端还是 mock。**零依赖**，只读一个构建期常量。
 *
 * <p>单独一个文件而不是从 `lib/api` 里导出：根 layout 要输出这个标记，
 * 而在服务端渲染的根布局里 import `lib/api` 会把整个 api 模块
 * （mock 数据、zustand store、契约类型）都拉进服务端构建 ——
 * 2026-09-01 试过一次，构建直接挂在一个毫不相干的地方：
 * `C_END_THEMES.map is not a function`（循环依赖导致某个导出还没初始化）。
 *
 * <p>判据与 `lib/api/index.ts` 必须一致：**`!== "0"` 就是 mock**。
 * 默认值是 mock，所以漏配 = 静默退回 mock —— 这正是这个标记要暴露的东西。
 */
export const IS_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "0";

/** 给 HTML meta 用的字面值 */
export const API_MODE = IS_MOCK ? "mock" : "http";
