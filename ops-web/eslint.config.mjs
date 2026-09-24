// ops-web 的 ESLint：**只开 react-hooks/rules-of-hooks 一条**，由 pre-push 执行。
//
// 为什么只有一条：2026-09-24 线上白屏（React #310）—— SecondaryNav 的两个 Hook 写在
// `return null` 之后，看板页点进商户页才崩，直接刷新不崩；tsc、vitest、构建全绿。
// 这类错只有这条规则能在静态阶段抓到。其余风格规则不在这里立：一次开一堆，
// 存量报错成百上千，闸门第一天就得加白名单，然后就没人看了。
//
// lib/hooks-after-early-return.test.ts 是之前的启发式扫描，留着当第二把尺：
// 它只认「return 之后的 useXxx(」，这里认的是完整的 Hook 规则（条件、循环、嵌套函数）。
import tsParser from "@typescript-eslint/parser";
import reactHooks from "eslint-plugin-react-hooks";

export default [
  {
    ignores: [".next/**", "out/**", "node_modules/**", "**/*.d.ts"],
  },
  {
    files: ["app/**/*.{ts,tsx}", "components/**/*.{ts,tsx}", "lib/**/*.{ts,tsx}"],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    // 不认行内的 eslint-disable：代码里留有给别的插件写的禁用注释（@next/next、
    // @typescript-eslint），这里没装那些插件，照认会报「规则不存在」。
    // 顺带的好处：没人能用一行注释把 rules-of-hooks 关掉。
    linterOptions: { noInlineConfig: true, reportUnusedDisableDirectives: "off" },
    plugins: { "react-hooks": reactHooks },
    rules: {
      "react-hooks/rules-of-hooks": "error",
    },
  },
];
