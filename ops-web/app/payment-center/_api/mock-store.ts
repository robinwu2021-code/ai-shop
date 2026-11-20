// 支付中心 mock 的公共件：分页、关键字筛选、乐观锁、延迟。
// mock 按真实契约走（版本号冲突、状态不允许都会抛错），这样 mock 上点得通的路径，接真接口也点得通。
import type { PayDetail, PayPage } from "./client";

export const mockDelay = <T,>(v: T, ms = 150) => new Promise<T>((r) => setTimeout(() => r(v), ms));

export class MockPayError extends Error {
  reasonCode: string;
  constructor(reasonCode: string, message: string) { super(message); this.reasonCode = reasonCode; }
}

export interface MockQuery { page?: number; size?: number; keyword?: string; [k: string]: unknown }

/** 通用列表：按 keyword 在给定字段里模糊匹配，其余非空查询参数按等值过滤，再分页。 */
export function pageOf<T extends object>(rows: T[], q: MockQuery, searchKeys: (keyof T)[]): Promise<PayPage<T>> {
  const page = Math.max(1, Number(q.page ?? 1));
  const size = Math.max(1, Number(q.size ?? 20));
  const kw = typeof q.keyword === "string" ? q.keyword.trim().toLowerCase() : "";
  const filters = Object.entries(q).filter(([k, v]) => !["page", "size", "keyword"].includes(k) && v !== undefined && v !== "");
  const out = rows.filter((r) => {
    const rec = r as Record<string, unknown>;
    if (kw && !searchKeys.some((k) => String(rec[k as string] ?? "").toLowerCase().includes(kw))) return false;
    return filters.every(([k, v]) => rec[k] === undefined || String(rec[k]) === String(v));
  });
  return mockDelay({ records: out.slice((page - 1) * size, page * size), total: out.length });
}

/** 带版本号的内存表：update 校验 expectedVersion，命中则版本 +1。 */
export class VersionedTable<T extends { version: number }> {
  constructor(private rows: T[], private idOf: (r: T) => string) {}
  all(): T[] { return this.rows; }
  find(id: string): T | undefined { return this.rows.find((r) => this.idOf(r) === id); }
  get(id: string): T {
    const r = this.find(id);
    if (!r) throw new MockPayError("NOT_FOUND", "记录不存在");
    return r;
  }
  detail(id: string, allowedActions?: (r: T) => string[]): Promise<PayDetail<T>> {
    try {
      const r = this.get(id);
      return mockDelay({ record: r, version: r.version, allowedActions: allowedActions?.(r) });
    } catch (e) { return Promise.reject(e); }
  }
  update(id: string, patch: Partial<T>, expectedVersion?: number): Promise<T> {
    try {
      const cur = this.get(id);
      if (expectedVersion != null && cur.version !== expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
      const next = { ...cur, ...patch, version: cur.version + 1 };
      this.rows = this.rows.map((r) => (this.idOf(r) === id ? next : r));
      return mockDelay(next);
    } catch (e) { return Promise.reject(e); }
  }
  insert(row: T): Promise<T> { this.rows = [row, ...this.rows]; return mockDelay(row); }
}
