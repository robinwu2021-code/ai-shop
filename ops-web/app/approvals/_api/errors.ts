// OpsKit 错误键 → 文案（TDD-OpsKit审批对接 §1.5）。error-keys.json 从 OpsKit 仓库 docs/error-keys.json 原样同步
// （128 条，zh / en / ar）；错误体的 error 与详情里的 mine.whyNot 共用这一份（契约 §3.2：whyNot 是错误码）。
// 繁体由简体转换；查不到的键显示服务端 message，再不行显示键本身 —— 宁可露出键，也不吞掉原因。
import keys from "./error-keys.json";
import { toTraditional } from "@/lib/i18n/zh-tw";
import { OpsKitError } from "./client";

type Entry = { status?: number; zh?: string; en?: string; ar?: string };
const TABLE = keys as Record<string, Entry>;

export function explainKey(key: string | undefined | null, locale: string, args?: Record<string, unknown>): string | null {
  if (!key) return null;
  const e = TABLE[key];
  if (!e) return null;
  const text = locale === "en" ? e.en : locale === "ar" ? e.ar ?? e.en : locale === "zh-TW" ? (e.zh ? toTraditional(e.zh) : undefined) : e.zh;
  if (!text) return null;
  return text.replace(/\{(\w+)\}/g, (m, k: string) => (args && args[k] !== undefined ? String(args[k]) : m));
}

export function explainError(e: unknown, locale: string): string {
  if (e instanceof OpsKitError) {
    const text = explainKey(e.key, locale, e.args) ?? e.message ?? e.key ?? `HTTP ${e.status}`;
    return e.traceId ? `${text}（trace ${e.traceId}）` : text;
  }
  return e instanceof Error ? e.message : String(e);
}
