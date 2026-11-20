import { describe, expect, it } from "vitest";
import { DRAFT_PREFIX, DRAFT_TTL_MS, createDraftStore, draftKey, type DraftRef } from "./draft";

/** 最小 Storage 实现：Map 背书，可注入写入失败。 */
function memoryStorage(opts: { failWrites?: boolean } = {}): Storage {
  const m = new Map<string, string>();
  return {
    get length() { return m.size; },
    clear: () => m.clear(),
    getItem: (k) => (m.has(k) ? m.get(k)! : null),
    key: (i) => [...m.keys()][i] ?? null,
    removeItem: (k) => { m.delete(k); },
    setItem: (k, v) => { if (opts.failWrites) throw new Error("QuotaExceeded"); m.set(k, String(v)); },
  };
}

const ref: DraftRef = { operator: "alice", page: "pay-orders", action: "edit-remark", id: "P001" };

describe("draftKey", () => {
  it("包含操作人、页面、操作与对象，新建记为 new", () => {
    expect(draftKey(ref)).toBe(`${DRAFT_PREFIX}alice:pay-orders:edit-remark:P001`);
    expect(draftKey({ ...ref, id: null })).toBe(`${DRAFT_PREFIX}alice:pay-orders:edit-remark:new`);
  });
  it("对各段做编码，冒号不会串段", () => {
    expect(draftKey({ ...ref, operator: "a:b" })).toContain("a%3Ab:pay-orders");
  });
});

describe("createDraftStore", () => {
  it("写入后可读回，带保存时间与基线版本", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    expect(store.write(ref, { remark: "hi" }, { baseVersion: 3, now: 1000 })).toBe(true);
    expect(store.read(ref, 2000)).toEqual({ schemaVersion: 1, savedAt: 1000, baseVersion: 3, values: { remark: "hi" } });
  });

  it("敏感字段不落盘", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    store.write(ref, { remark: "hi", bankAccount: "6222020000001234" }, { sensitiveKeys: ["bankAccount"], now: 1 });
    const raw = storage.getItem(draftKey(ref))!;
    expect(raw).not.toContain("6222020000001234");
    expect(store.read(ref, 2)?.values).toEqual({ remark: "hi" });
  });

  it("超过 7 天的草稿视为不存在并被清掉", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    store.write(ref, { remark: "old" }, { now: 0 });
    expect(store.read(ref, DRAFT_TTL_MS + 1)).toBeNull();
    expect(storage.getItem(draftKey(ref))).toBeNull();
  });

  it("换操作人读不到别人的草稿", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    store.write(ref, { remark: "mine" }, { now: 1 });
    expect(store.read({ ...ref, operator: "bob" }, 2)).toBeNull();
  });

  it("clear 删除单条；clearOperator 只清本人", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    store.write(ref, { a: 1 }, { now: 1 });
    store.write({ ...ref, id: "P002" }, { a: 2 }, { now: 1 });
    store.write({ ...ref, operator: "bob" }, { a: 3 }, { now: 1 });
    store.clear(ref);
    expect(store.read(ref, 2)).toBeNull();
    store.clearOperator("alice");
    expect(store.list("alice", undefined, 2)).toEqual([]);
    expect(store.list("bob", undefined, 2)).toHaveLength(1);
  });

  it("list 按页面过滤，按保存时间倒序，跳过过期与损坏的条目", () => {
    const storage = memoryStorage();
    const store = createDraftStore(() => storage);
    store.write(ref, { a: 1 }, { now: 10 });
    store.write({ ...ref, id: "P002" }, { a: 2 }, { now: 20 });
    store.write({ ...ref, page: "other" }, { a: 3 }, { now: 30 });
    storage.setItem(`${DRAFT_PREFIX}alice:pay-orders:edit-remark:BAD`, "{not json");
    const items = store.list("alice", "pay-orders", 40);
    expect(items.map((d) => d.ref.id)).toEqual(["P002", "P001"]);
    expect(items[0]).toMatchObject({ savedAt: 20, ref: { operator: "alice", page: "pay-orders", action: "edit-remark" } });
  });

  it("存储不可用或写入失败时不抛错，write 返回 false", () => {
    expect(createDraftStore(() => null).write(ref, { a: 1 })).toBe(false);
    expect(createDraftStore(() => null).read(ref)).toBeNull();
    expect(createDraftStore(() => memoryStorage({ failWrites: true })).write(ref, { a: 1 })).toBe(false);
    expect(createDraftStore(() => { throw new Error("SecurityError"); }).list("alice")).toEqual([]);
  });

  it("schemaVersion 不认识的条目丢弃", () => {
    const storage = memoryStorage();
    storage.setItem(draftKey(ref), JSON.stringify({ schemaVersion: 99, savedAt: 1, values: {} }));
    expect(createDraftStore(() => storage).read(ref, 2)).toBeNull();
  });
});
