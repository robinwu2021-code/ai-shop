// 实现只有一份，在 scripts/lib/backend-modules.mjs —— 守卫与生成器共用同一把尺，
// 两边各抄一份的话，改了一边、另一边继续少扫，而少扫在这两类调用方里都不报错。
// @ts-expect-error 纯 JS 模块，没有 .d.ts
export { backendModules, assertScanScope } from "../../../scripts/lib/backend-modules.mjs";
