import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const KEY = "err.inventory.shared_source";
const LOCALES = ["messages.properties", "messages_en.properties", "messages_ar.properties"] as const;
const PRODUCERS = ["StockReconcileService", "InventoryWritebackService"] as const;

function sharedSourceMessage(file: string): string {
  const src = readFileSync(join(ROOT, "backend/shop-app/src/main/resources/i18n", file), "utf8");
  const entries = src.split(/\r?\n/).filter((line) => line.startsWith(`${KEY}=`));
  expect(entries, `${file}: shared-source key must occur exactly once`).toHaveLength(1);
  return entries[0]!.slice(KEY.length + 1);
}

function sharedSourceArguments(producer: string): string[] {
  const src = readFileSync(join(ROOT,
    `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/${producer}.java`), "utf8");
  // Accept the existing qualified call and normal imports; read both real producers.
  const calls = [...src.matchAll(/BizException\.of\(\s*(?:[\w.]*ErrorCode\.)STOCK_SHARED_SOURCE\s*,([^)]*)\)/g)];
  expect(calls, `${producer}: expected one shared-source exception producer`).toHaveLength(1);
  return calls[0]![1]!.split(",").map((arg) => arg.trim());
}

describe("shared stock source message contract", () => {
  it.each(PRODUCERS)("%s supplies current store then conflicting store", (producer) => {
    expect(sharedSourceArguments(producer)).toEqual(["storeNo", "other"]);
  });

  it.each(LOCALES)("%s displays both supplied store slots", (file) => {
    const message = sharedSourceMessage(file);
    const indices = [...message.matchAll(/\{(\d+)(?:,[^{}]*)?\}/g)].map((match) => Number(match[1]));
    expect([...new Set(indices)].sort(), `${file}: current {0} and conflicting {1} stores are both required`)
      .toEqual([0, 1]);

    const sampleStores: Record<string, string> = { storeNo: "CURRENT-STORE-17", other: "CONFLICTING-STORE-92" };
    for (const producer of PRODUCERS) {
      const args = sharedSourceArguments(producer).map((name) => sampleStores[name]);
      expect(args).toEqual(["CURRENT-STORE-17", "CONFLICTING-STORE-92"]);
      // Literal slot projection checks consumer-visible data coverage only.
      // This is not a MessageFormat implementation or a production Java runtime test.
      const display = message.replace(/\{([01])\}/g, (_, index: string) => args[Number(index)]!);
      expect(display).toContain("CURRENT-STORE-17");
      expect(display).toContain("CONFLICTING-STORE-92");
      expect(display).not.toMatch(/\{\d+[^{}]*\}/);
    }
  });
});
