// 运营端品牌（名称、标识、环境）。真源是公开文件 brand.json，见 TDD-ops-品牌名称与登录页 §1。
// 这里只放纯函数：默认值与合并规则，便于单测；加载与订阅在 lib/stores/brand.ts。
import type { Locale } from "@/lib/stores/locale";

export type BrandEnv = "" | "test" | "staging";

export interface Brand {
  /** 名称：浏览器标签、登录卡片、侧栏、桌面通知 */
  name: string;
  /** 方形标识里的字（1–2 个字符）；有 logoUrl 时不显示 */
  mark: string;
  /** 图片标识；空 = 用 mark */
  logoUrl: string;
  /** 环境：空 = 生产（不显示标签） */
  env: BrandEnv;
}

export interface BrandFile extends Partial<Record<keyof Brand, unknown>> {
  locales?: Partial<Record<Locale, Partial<Record<keyof Brand, unknown>>>>;
}

const ENVS: readonly BrandEnv[] = ["", "test", "staging"];

/** 代码默认值，再被构建时环境变量覆盖（最后一层兜底，任何情况下登录页都有名称可显示）。 */
export const BUILD_BRAND: Brand = {
  name: process.env.NEXT_PUBLIC_BRAND_NAME || "NearGo平台运营",
  mark: process.env.NEXT_PUBLIC_BRAND_MARK || "N",
  logoUrl: "",
  env: (ENVS as readonly string[]).includes(process.env.NEXT_PUBLIC_BRAND_ENV ?? "") ? (process.env.NEXT_PUBLIC_BRAND_ENV as BrandEnv) : "",
};

/** 只接受类型正确的字段；其余逐项回落到 base —— 配置写错一项不该让整份失效。 */
function overlay(base: Brand, patch: Partial<Record<keyof Brand, unknown>> | undefined): Brand {
  if (!patch || typeof patch !== "object") return base;
  const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : undefined);
  const env = typeof patch.env === "string" && (ENVS as readonly string[]).includes(patch.env) ? (patch.env as BrandEnv) : undefined;
  return {
    name: str(patch.name) ?? base.name,
    mark: str(patch.mark)?.slice(0, 2) ?? base.mark,
    logoUrl: typeof patch.logoUrl === "string" ? patch.logoUrl.trim() : base.logoUrl,
    env: env ?? base.env,
  };
}

/** 构建默认值 ← brand.json 顶层 ← brand.json.locales[locale]。 */
export function resolveBrand(file: unknown, locale: Locale, base: Brand = BUILD_BRAND): Brand {
  if (!file || typeof file !== "object" || Array.isArray(file)) return base;
  const f = file as BrandFile;
  return overlay(overlay(base, f), f.locales?.[locale]);
}
