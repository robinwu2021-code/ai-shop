import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { SEEDED_USERNAMES } from "./seeded-accounts";

/**
 * 种子账号名单必须与 `DevSeeder.java` 逐条相等。
 *
 * 手抄的名单会过期，而**过期的名单比没有更糟**：新加一个种子账号而这里没跟上，
 * 它在员工列表里看起来就是个正常账号 —— 一个口令是「登录名+123」的正常账号。
 */
const DEV_SEEDER = join(
  import.meta.dirname, "../../backend/shop-app/src/main/java/ai/neargo/shop/config/DevSeeder.java",
);

describe("演示种子账号", () => {
  it("★★★ 名单与 DevSeeder.java 逐条相等", () => {
    const src = readFileSync(DEV_SEEDER, "utf8");
    const fromJava = [...src.matchAll(/seedStaff\(staffMapper,\s*roleMemberMapper,\s*"([a-z]+)"/g)]
      .map((m) => m[1]!);

    // 对照量：解析失效时不能静默通过 —— 一个都没扫到与「后端删光了种子」长得一样
    expect(fromJava.length, "一个 seedStaff 调用都没扫到 —— DevSeeder 的写法变了？")
      .toBeGreaterThan(5);

    expect([...SEEDED_USERNAMES].sort(), "名单与 DevSeeder 漂了：种子账号的口令是「登录名+123」，"
      + "漏一个就是列表里多一个看起来正常、实则弱口令的账号")
      .toEqual(fromJava.sort());
  });
});
