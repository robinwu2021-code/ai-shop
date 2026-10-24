import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

/** Flatten the actual locale object, including quoted/minified/nested keys. */
export function localeEntries(file) {
  const source = readFileSync(file, "utf8");
  const literal = source.match(/export\s+default\s*({[\s\S]*})\s*(?:as\s+const)?\s*;?\s*$/)?.[1];
  if (!literal) throw new Error(`Cannot parse locale object: ${file}`);
  const object = runInNewContext(`(${literal})`, Object.create(null), {
    timeout: 1000, contextCodeGeneration: { strings: false, wasm: false },
  });
  const entries = new Map();
  function walk(value, prefix) {
    if (typeof value === "string") { entries.set(prefix, value); return; }
    if (value && typeof value === "object") {
      for (const [key, child] of Object.entries(value)) walk(child, prefix ? `${prefix}.${key}` : key);
    }
  }
  walk(object, "");
  return entries;
}
