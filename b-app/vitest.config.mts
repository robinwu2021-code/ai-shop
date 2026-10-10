import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

/*
 * b-app 的单元测试。**与 vite.config.mts 分开**，理由同 c-app 那份：
 * 那份挂着 uni 插件与 UnoCSS，它们要一个真实的小程序/H5 编译上下文，node 里跑不起来。
 *
 * 与 c-app 那份的差别：**这里不装 vue 插件、不要 DOM**。
 * 第一批只测 `src/utils` / `src/shared` 里的纯函数 —— 那些是「一个值进、一个值出」
 * 的业务规则，出错的后果具体（提交载荷少一半、覆盖被清掉），而且不会跟正在进行的
 * UI 重构撞车。
 *
 * ⚠️ **将来要加组件测试时，照抄 c-app 的三样，别只加 `@vitejs/plugin-vue` + happy-dom**：
 *   ① `@ai-shop/ui` 别名（指向 `packages/ui/src`）
 *   ② `tests/setup.ts` 里按 easycom 规则 eager glob **全局注册** `sh-*`
 *   ③ b-app 的 easycom 规则在 `b-app/src/pages.json`，与 c-app 同形
 *
 * 少了①②的后果不是报错，是**断言在一个空的 DOM 上求值**：uni 的 easycom
 * 在 vitest 里不生效，`<sh-*>` 会被 Vue 当成未知自定义元素 —— 标签在、内容不在、
 * 插槽不渲染。c-app 为此付过代价（`b19355fb`）：库件收编之后 16 条一次性变红，
 * 失败信息全是「期望包含 xxx，实际是空字符串」，**没有一条指向「这个组件压根没渲染」**。
 * 也就是说在那之前，凡是穿到库件里的断言都是假的。
 *
 * 为什么现在才有：b-app 此前**一个测试文件都没有**，pre-push 对两端 uni-app 只跑
 * vue-tsc。而类型检查看不见「这个分支永远进不去」「只提交了一半列表」这类事 ——
 * c-app 那三条红（`show` → `visible` 改名）就是这么活了不知道多久的。
 */
export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "@shared": fileURLToPath(new URL("../packages/shared/src", import.meta.url)),
    },
  },
  test: {
    include: ["tests/**/*.test.ts"],
  },
});
