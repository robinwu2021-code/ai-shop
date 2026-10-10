import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";

/**
 * 后端所有含 Java 源码的模块目录（相对 `backend/`）。
 *
 * 硬编码清单会漏 —— 而在「找出违规」型的闸门与生成器里，
 * **漏扫恰好表现为「没有违规」**，与全绿长得一模一样。
 * 只扫一层的自动发现同样会漏：`backend/pay/pay-domain` 是嵌套的。
 *
 * 这份实现同时被 packages/shared/tests 的守卫与 scripts/ 的生成器引用，
 * 改这里等于同时改两边 —— 不要各自复制一份。
 */
export function backendModules(backendDir) {
  const out = [];
  const hasSrc = (rel) => existsSync(join(backendDir, rel, "src/main/java"));
  for (const d of readdirSync(backendDir, { withFileTypes: true })) {
    if (!d.isDirectory()) continue;
    if (hasSrc(d.name)) { out.push(d.name); continue; }
    // 第二层：backend/pay/pay-domain 这种
    for (const s of readdirSync(join(backendDir, d.name), { withFileTypes: true })) {
      if (s.isDirectory() && hasSrc(join(d.name, s.name))) out.push(join(d.name, s.name));
    }
  }
  return out;
}

/** 扫描面的对照量：漏扫在调用方不会报错，所以在这里当场抛。 */
export function assertScanScope(mods) {
  // 两个嵌套目录各点一个名：pay/ 与 job/ 都在第二层，少扫一整组时这里当场抛
  const want = ["shop-app", "pay/pay-domain", "job/job-core"];
  const missing = want.filter((m) => !mods.includes(m));
  if (missing.length || mods.length < 10) {
    throw new Error(
      `后端模块扫描面不对：${mods.length} 个 [${mods.join(", ")}]，缺 [${missing.join(", ")}]。\n` +
      "  期望至少 10 个、且含 shop-app、pay/pay-domain、job/job-core。");
  }
  return mods;
}
