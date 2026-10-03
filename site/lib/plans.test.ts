/**
 * 官网上的档位与数据库里的三档种子同源。
 *
 * 漂移的症状很隐蔽：运营在 `sys_merchant_plan_def` 里把 PRO 的门店额度从 3 调到 5，
 * 官网还写着 3 —— 页面照常渲染，没有任何东西会报错，要有商家来问才发现。
 * 所以这里直接解析那份迁移，而不是「两边都记得改」。
 *
 * 一期档位只在 V150 建表时种下，没有后续 UPDATE。真出现了第二份迁移改它，
 * 下面的 `SEED_FILE` 断言会先红 —— 那时要做的是改这个测试去读新的真源，
 * 而不是把数字抄一遍。
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { PLANS } from "./plans";

const MIGRATIONS = join(import.meta.dirname, "../../backend/shop-app/src/main/resources/db/migration");
const SEED_FILE = "V150__merchant_plan.sql";
const SQL = readFileSync(join(MIGRATIONS, SEED_FILE), "utf8");

/** `SELECT 'PRO', '成长版', 3, 3, 1, 14, …` → 一档 */
function seeded() {
  const re = /SELECT\s+'(FREE|PRO|CHAIN)',\s*'([^']+)',\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)/g;
  return [...SQL.matchAll(re)].map((m) => ({
    code: m[1]!,
    dbName: m[2]!,
    storeQuota: Number(m[3]),
    staffQuota: Number(m[4]),
    crossStoreStats: m[5] === "1",
    trialDays: Number(m[6]),
  }));
}

describe("档位：官网与 sys_merchant_plan_def 种子同源", () => {
  it("解析得出三档（解析不出来说明迁移的写法变了，先修这里）", () => {
    expect(seeded().map((s) => s.code)).toEqual(["FREE", "PRO", "CHAIN"]);
  });

  it.each(PLANS.map((p) => [p.code, p] as const))("%s 的额度与能力位一致", (code, plan) => {
    const db = seeded().find((s) => s.code === code)!;
    expect(
      {
        storeQuota: plan.storeQuota,
        staffQuota: plan.staffQuota,
        crossStoreStats: plan.crossStoreStats,
        trialDays: plan.trialDays,
      },
      `官网 ${plan.name}(${code}) 与 ${SEED_FILE} 对不上 —— 真源是迁移`,
    ).toEqual({
      storeQuota: db.storeQuota,
      staffQuota: db.staffQuota,
      crossStoreStats: db.crossStoreStats,
      trialDays: db.trialDays,
    });
  });

  /**
   * 官网叫「专业版」而 B 端叫「成长版」，是 2026-08-20 有意选的分叉。
   * 这条断言不反对分叉，它只钉住**分叉的另一端**：DB 的名字一旦改了
   * （比如后来做了那条对齐用的 UPDATE 迁移），这里会红，提醒把 `dbName` 一起收掉。
   */
  it.each(PLANS.map((p) => [p.code, p] as const))("%s 记着 B 端在用的那个名字", (code, plan) => {
    const db = seeded().find((s) => s.code === code)!;
    expect(plan.dbName, `${code} 在库里叫「${db.dbName}」，官网的 dbName 没跟上`).toBe(db.dbName);
  });

  it("没有第二份迁移在改档位定义（有了就得换真源）", () => {
    const others = readdirSync(MIGRATIONS).filter(
      (f) => f !== SEED_FILE && readFileSync(join(MIGRATIONS, f), "utf8").includes("sys_merchant_plan_def"),
    );
    expect(others, `这些迁移也动了档位定义：${others.join(", ")}`).toEqual([]);
  });
});
