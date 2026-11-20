// 抽屉编辑的本地草稿（TDD-支付中心运营页面 §2.4）。纯函数 + 可注入 Storage，便于单测。
//
// 为什么放 localStorage 而不是 sessionStorage：要扛住「关标签页、明天再来」。
// 为什么键里带操作人：同一台电脑换号登录，不能看到上一个人没提交的内容。
// 为什么剔除敏感字段：银行账号、密钥、证件号写进浏览器存储等于明文落盘，
// 宁可让人重填这几个字段，也不留在本机。
//
// 所有存储访问都包 try/catch：隐私模式、站点数据被禁用时 Storage 可能为 null 或一碰就抛。
// 失败的写入返回 false，由界面提示「草稿无法本地保存」，而不是让整个表单崩掉。

export const DRAFT_PREFIX = "neargo-ops-draft:v1:";
export const DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const SCHEMA_VERSION = 1 as const;

export interface DraftRef {
  /** 操作人（登录名）。不能为空 —— 为空时调用方不该启用草稿。 */
  operator: string;
  /** 页面标识，如 `pay-orders`。 */
  page: string;
  /** 操作标识，即 ActionSpec.key。 */
  action: string;
  /** 对象编号；新建时为空，键里记为 `new`。 */
  id?: string | null;
}

export interface DraftRecord<V> {
  schemaVersion: typeof SCHEMA_VERSION;
  savedAt: number;
  /** 打开编辑时服务端记录的版本；恢复时与当前版本比对，不一致即冲突。 */
  baseVersion?: string | number | null;
  values: V;
}

export interface DraftListItem {
  ref: DraftRef;
  key: string;
  savedAt: number;
}

export interface DraftStore {
  read<V = Record<string, unknown>>(ref: DraftRef, now?: number): DraftRecord<V> | null;
  write(ref: DraftRef, values: Record<string, unknown>, opts?: {
    sensitiveKeys?: readonly string[];
    baseVersion?: string | number | null;
    now?: number;
  }): boolean;
  clear(ref: DraftRef): void;
  list(operator: string, page?: string, now?: number): DraftListItem[];
  clearOperator(operator: string): void;
}

const enc = encodeURIComponent;

export function draftKey(ref: DraftRef): string {
  return DRAFT_PREFIX + [ref.operator, ref.page, ref.action, ref.id || "new"].map(enc).join(":");
}

function parseKey(key: string): DraftRef | null {
  if (!key.startsWith(DRAFT_PREFIX)) return null;
  const parts = key.slice(DRAFT_PREFIX.length).split(":");
  if (parts.length !== 4) return null;
  try {
    const [operator, page, action, id] = parts.map(decodeURIComponent);
    return { operator, page, action, id: id === "new" ? null : id };
  } catch {
    return null;
  }
}

export function stripSensitive<V extends Record<string, unknown>>(values: V, keys: readonly string[] = []): Partial<V> {
  if (!keys.length) return { ...values };
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) if (!keys.includes(k)) out[k] = v;
  return out as Partial<V>;
}

function parseRecord<V>(raw: string | null): DraftRecord<V> | null {
  if (!raw) return null;
  try {
    const rec = JSON.parse(raw) as DraftRecord<V>;
    if (!rec || rec.schemaVersion !== SCHEMA_VERSION || typeof rec.savedAt !== "number" || typeof rec.values !== "object") return null;
    return rec;
  } catch {
    return null;
  }
}

export function createDraftStore(getStorage: () => Storage | null | undefined): DraftStore {
  const storage = (): Storage | null => {
    try { return getStorage() ?? null; } catch { return null; }
  };
  const remove = (s: Storage, key: string) => { try { s.removeItem(key); } catch { /* 忽略 */ } };
  const keysOf = (s: Storage): string[] => {
    const keys: string[] = [];
    try {
      for (let i = 0; i < s.length; i++) { const k = s.key(i); if (k?.startsWith(DRAFT_PREFIX)) keys.push(k); }
    } catch { /* 忽略 */ }
    return keys;
  };

  return {
    read<V>(ref: DraftRef, now = Date.now()) {
      const s = storage();
      if (!s) return null;
      const key = draftKey(ref);
      let raw: string | null = null;
      try { raw = s.getItem(key); } catch { return null; }
      const rec = parseRecord<V>(raw);
      if (!rec) { if (raw != null) remove(s, key); return null; }
      if (now - rec.savedAt > DRAFT_TTL_MS) { remove(s, key); return null; }
      return rec;
    },
    write(ref, values, opts = {}) {
      const s = storage();
      if (!s) return false;
      const rec: DraftRecord<Record<string, unknown>> = {
        schemaVersion: SCHEMA_VERSION,
        savedAt: opts.now ?? Date.now(),
        baseVersion: opts.baseVersion ?? null,
        values: stripSensitive(values, opts.sensitiveKeys),
      };
      try { s.setItem(draftKey(ref), JSON.stringify(rec)); return true; } catch { return false; }
    },
    clear(ref) {
      const s = storage();
      if (s) remove(s, draftKey(ref));
    },
    list(operator, page, now = Date.now()) {
      const s = storage();
      if (!s) return [];
      const out: DraftListItem[] = [];
      for (const key of keysOf(s)) {
        const ref = parseKey(key);
        if (!ref || ref.operator !== operator || (page && ref.page !== page)) continue;
        let raw: string | null = null;
        try { raw = s.getItem(key); } catch { continue; }
        const rec = parseRecord(raw);
        if (!rec || now - rec.savedAt > DRAFT_TTL_MS) { remove(s, key); continue; }
        out.push({ ref, key, savedAt: rec.savedAt });
      }
      return out.sort((a, b) => b.savedAt - a.savedAt);
    },
    clearOperator(operator) {
      const s = storage();
      if (!s) return;
      for (const key of keysOf(s)) if (parseKey(key)?.operator === operator) remove(s, key);
    },
  };
}

/** 浏览器默认实例。服务端渲染/静态导出阶段 window 不存在，存储视为不可用。 */
export const draftStore: DraftStore = createDraftStore(() => (typeof window === "undefined" ? null : window.localStorage));
