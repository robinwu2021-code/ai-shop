#!/usr/bin/env node
/**
 * 进销存独立服务的独立性闸门（TDD-进销存独立服务与多业态 §2.1：I2 · I3 · I4 的纯读文件版）。
 *
 * 进销存已经搬进 backend/inventory/，是一个**不依赖 ai-shop** 的服务（ADR-046）。
 * 这个承诺最容易坏在「先 import 一个商城工具类」「先加一个 shop 依赖」「先引一个仓库里的相对路径」——
 * 每一次都局部合理，攒到第三次，这个目录就搬不走了，而没有任何构建会报错。
 *
 * 三条检查，全部纯读文件，几十毫秒：
 *   I2 · Java 不许 import ai.neargo.shop.*（ArchUnit 在 inventory-svc 的测试里还有一道按字节码的）
 *   I3 · pom 不许依赖 ai.neargo.shop 下除 job-api / svc-client 以外的构件（enforcer 在构建期还有一道）
 *   I4 · 目录里任何相对路径都不许指出 backend/inventory/（整目录搬走时会断）
 *
 * 用法：node scripts/check-inventory-independence.mjs   （pre-push 调用，非零退出即拦）
 */
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, dirname, resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SERVICE = join(ROOT, "backend/inventory");
const ALLOWED_SHOP_ARTIFACTS = new Set(["job-api", "svc-client"]);
const TEXT = /\.(java|xml|yml|yaml|properties|md|sql|imports|json)$/;

if (!existsSync(SERVICE)) {
  console.error("✗ backend/inventory/ 不存在");
  process.exit(1);
}

const files = [];
(function walk(d) {
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (statSync(p).isDirectory()) {
      if (f === "target" || f === "node_modules" || f === ".git") continue;
      walk(p);
    } else if (TEXT.test(f)) {
      files.push(p);
    }
  }
})(SERVICE);

const errs = [];
const rel = (p) => relative(ROOT, p);

for (const file of files) {
  const src = readFileSync(file, "utf8");
  const lines = src.split(/\r?\n/);

  if (file.endsWith(".java")) {
    lines.forEach((line, i) => {
      if (/^\s*import\s+(static\s+)?ai\.neargo\.shop\./.test(line)) {
        errs.push(`${rel(file)}:${i + 1}  I2 import 了商城代码：${line.trim()}`);
      }
    });
  }

  if (file.endsWith("pom.xml")) {
    const deps = src.matchAll(/<groupId>\s*ai\.neargo\.shop\s*<\/groupId>\s*<artifactId>\s*([^<\s]+)\s*<\/artifactId>/g);
    for (const m of deps) {
      if (!ALLOWED_SHOP_ARTIFACTS.has(m[1])) {
        errs.push(`${rel(file)}  I3 依赖了 ai-shop 构件 ai.neargo.shop:${m[1]}（白名单只有 job-api、svc-client）`);
      }
    }
  }

  // I4：相对路径。Markdown 链接、pom 的 relativePath、以及代码 / 配置里出现的 ../ 字面量
  const candidates = [
    ...src.matchAll(/\]\((\.\.\/[^)\s#]*)/g),
    ...src.matchAll(/<relativePath>\s*(\.\.[^<\s]*)\s*<\/relativePath>/g),
    ...src.matchAll(/["'`](\.\.\/[^"'`\s]*)["'`]/g),
  ];
  for (const m of candidates) {
    const target = resolve(dirname(file), m[1]);
    if (!target.startsWith(SERVICE + "/") && target !== SERVICE) {
      errs.push(`${rel(file)}  I4 相对路径指出了服务目录：${m[1]}`);
    }
  }
}

if (errs.length) {
  console.error(`✗ 进销存独立性闸门：${errs.length} 处`);
  for (const e of errs) console.error("  " + e);
  console.error("  进销存是独立服务（ADR-046），白名单与理由见 docs/technical/design/TDD-进销存独立服务与多业态.md §2.1");
  process.exit(1);
}
console.log(`✅ 进销存独立性：${files.length} 个文件，无 shop import、无越界依赖、无指出目录的相对路径`);
