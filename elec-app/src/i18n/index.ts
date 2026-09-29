// 元器件只做国内，首版只有 zh-CN 一份词条（TDD-元器件-前端独立与通知矩阵 §1.4）。
// 仍走 createAppI18n：组件库（sh-scaffold 的标题、重试、确认框）读的是 i18n 实例，
// 页面标题也走 title-key —— 界面清单生成器按它取标题。页面正文直接写中文。
import { createAppI18n } from "@ai-shop/ui/i18n";
import zhCN from "./locale/zh-CN";

export const i18n = createAppI18n({ "zh-CN": zhCN });
