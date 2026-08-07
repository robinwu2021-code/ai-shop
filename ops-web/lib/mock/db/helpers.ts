// 通用查询/CRUD helper（提取自 powerbank/ops-web，分页口径改为 ai-shop 契约的 {records,...}）。
import type { Page, Archivable } from "@/lib/types";
import { fail, notFound } from "@/lib/biz-error";

export function paginate<T>(all: T[], page = 1, size = 10, filter?: (t: T) => boolean): Page<T> {
  const rows = filter ? all.filter(filter) : all;
  const start = (page - 1) * size;
  return { records: rows.slice(start, start + size), total: rows.length, page, size };
}

export const kwHit = (kw: string | undefined, ...fields: (string | null | undefined)[]) =>
  !kw || fields.some((f) => (f ?? "").toLowerCase().includes(kw.toLowerCase()));

/** 值为空（未传/空串）时不参与过滤；否则要求相等。筛选项一律走它，避免各写各的三元。 */
/**
 * 等值过滤。**支持逗号分隔的多值**（`"SUBMITTED,REVIEWING"`）——
 * 「入驻审核」这类视图筛的是"还没走完审核的那几档"，不是单个状态；
 * 没有这条的话页面只能自己拉全量再前端过滤，分页数就全错了。
 */
export const eqHit = (want: unknown, got: unknown) => {
  if (!want) return true;
  if (typeof want === "string" && want.includes(",")) return want.split(",").includes(String(got));
  return want === got;
};

/**
 * 数据域裁剪：q 里带了归属键就只留该归属的行。
 * ⚠️ mock 层也必须实现，否则「BD 只看得到自己商家」这条规则在前端开发期是假的，
 *    等接了后端才发现页面没为空列表做任何交代（矩阵 §2.3）。
 */
export const scopeHit = (
  q: { merchantNo?: string; communityNo?: string; pickupNo?: string },
  row: { merchantNo?: string; communityNo?: string; pickupNo?: string },
) =>
  eqHit(q.merchantNo, row.merchantNo) &&
  eqHit(q.communityNo, row.communityNo) &&
  eqHit(q.pickupNo, row.pickupNo);

/** 通用 mock 新增/编辑：有业务键→就地更新，无→生成键后置顶插入。返回落地记录。 */
export function upsert<T>(arr: T[], item: Partial<T>, keyField: keyof T, mkKey: () => string): T {
  const key = item[keyField] as unknown as string | undefined;
  if (key) {
    const i = arr.findIndex((x) => (x[keyField] as unknown as string) === key);
    if (i >= 0) { arr[i] = { ...arr[i], ...item }; return arr[i]; }
  }
  const created = { ...item, [keyField]: key || mkKey() } as T;
  arr.unshift(created);
  return created;
}

/**
 * 生成新业务号：前缀 + **现有同前缀号最大值 +1**。
 * 不用 `prefix + arr.length`：数据从 base+1 起编号或删过行时必然撞号。
 */
export const nextNo = (prefix: string, arr: unknown[], base = 900, keyField?: string) => {
  const re = new RegExp(`^${prefix}(\\d+)$`);
  let max = base - 1;
  for (const row of arr) {
    if (!row || typeof row !== "object") continue;
    const rec = row as Record<string, unknown>;
    const vals = keyField ? [rec[keyField]] : Object.values(rec);
    for (const v of vals) {
      if (typeof v !== "string") continue;
      const m = re.exec(v);
      if (m) max = Math.max(max, Number(m[1]));
    }
  }
  return `${prefix}${max + 1}`;
};

// ── G1 软删除：归档而非删除（契约禁止 delete*）──────────────────────────────

/**
 * 列表默认过滤已归档行的谓词。**每个可归档实体的 list 都必须串上它**。
 * `showArchived` 从查询参数来，可能是 boolean 也可能是 "1"/"true"（qs 会序列化成字符串）。
 */
export const liveHit = (row: { archivedAt?: string | null }, showArchived?: unknown) =>
  showArchived === true || showArchived === "1" || showArchived === "true" ? true : !row.archivedAt;

/** 归档/恢复落库：按业务键就地改 `archivedAt`。找不到直接抛——前端不该调到不存在的行。 */
export function setArchived<T extends Archivable>(
  arr: T[], keyField: keyof T, key: string, at: string | null,
): T {
  const i = arr.findIndex((x) => (x[keyField] as unknown as string) === key);
  if (i < 0) notFound("记录", "Record", key);
  arr[i] = { ...arr[i], archivedAt: at };
  return arr[i];
}

export const archiveRow = <T extends Archivable>(arr: T[], keyField: keyof T, key: string) =>
  setArchived(arr, keyField, key, new Date().toISOString());

export const unarchiveRow = <T extends Archivable>(arr: T[], keyField: keyof T, key: string) =>
  setArchived(arr, keyField, key, null);

/**
 * 状态机守卫：非法迁移**抛错**而不是静默放行。
 * mock 层强制状态机是本工程的硬约定（架构 §10.5）——否则页面会写出后端根本不允许的流程，
 * 等接后端才暴露，那时改的是页面结构不是一行判断。
 */
export function assertTransition<S extends string>(
  table: Record<S, S[]>, from: S, to: S, zhEntity: string, enEntity: string,
): void {
  if (!table[from]?.includes(to)) {
    fail(
      `${zhEntity}状态不允许从 ${from} 迁移到 ${to}`,
      `${enEntity} cannot move from ${from} to ${to}`,
    );
  }
}
