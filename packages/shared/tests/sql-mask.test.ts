import { describe, expect, it } from "vitest";
import { maskSqlNoise } from "../../../scripts/lib/sql-mask.mjs";

/**
 * `check-sql-portability` 匹配之前要先把**注释与字符串**盖掉。
 *
 * ## 这道断言在守什么
 *
 * 2026-08-30：有人改完 `V3__supplier.sql` 的排序规则，闸门**仍然红**——
 * 命中的是他注释里那个错误示例，而那一行永远不会被执行。
 *
 * 后果不是「误报烦人」这么轻：**它让人不敢在迁移注释里写出反例**，
 * 而「为什么不能这么写」恰恰最该写在离现场最近的地方。
 * 一道惩罚反例注释的闸门，是在系统性地删掉最有价值的那类注释。
 *
 * ## 为什么这份测试里有一条看起来多余的断言
 *
 * `stillCatchesRealSql` 是**对照量**。没有它，一个「把整份文件都盖成空格」的
 * 实现能让其余断言全绿 —— 而那个实现会把整道可移植性闸门变成永远通过。
 * 2026-08-28 在别处栽过一次：补的对照量恒为 0 而没人查，那条对照什么都没证明。
 */
describe("SQL 噪声遮盖", () => {
  const BAD = "utf8mb4_uca1400_ai_ci";

  it("★★★ 对照：写在真 SQL 里的方言关键词必须**仍然**留着 —— 否则这份测试什么都没证明", () => {
    const sql = `CREATE TABLE t (id BIGINT) COLLATE=${BAD};`;
    expect(maskSqlNoise(sql)).toContain(BAD);
  });

  it("★★★ 写在 -- 行注释里的不算 —— 它永远不会被数据库执行", () => {
    const sql = `-- 别再用 ${BAD}，MySQL 上建不起来\nCREATE TABLE t (id BIGINT);`;
    expect(maskSqlNoise(sql)).not.toContain(BAD);
  });

  it("★★ 块注释与 # 注释同理", () => {
    expect(maskSqlNoise(`/* 反例：${BAD} */ SELECT 1;`)).not.toContain(BAD);
    expect(maskSqlNoise(`# 反例：${BAD}\nSELECT 1;`)).not.toContain(BAD);
  });

  it("★★ 字符串字面量里的不算 —— 中文表注释里出现关键词是这个仓库的常态", () => {
    expect(maskSqlNoise(`COMMENT='历史遗留：曾用 ${BAD}'`)).not.toContain(BAD);
    // '' 是 SQL 里的转义引号，不是字符串结束 —— 认错了会把后面整段当成 SQL
    expect(maskSqlNoise(`COMMENT='它''s ${BAD}' , x=${BAD}`).match(/uca1400/g))
      .toHaveLength(1);
  });

  it("★★ 行号不能错位 —— 报错行号错了的闸门比没有闸门更费时间", () => {
    const sql = "-- 注释\n/* 跨\n行\n注释 */\nSELECT 1;";
    const masked = maskSqlNoise(sql);
    expect(masked).toHaveLength(sql.length);
    expect(masked.split("\n")).toHaveLength(sql.split("\n").length);
    // 换行符本身不能被盖掉，否则下游数行数会少
    expect([...masked].filter((c) => c === "\n")).toHaveLength(4);
  });

  it("★ `--中文` 不是注释 —— MariaDB 里它是语法错，替它打掩护等于放行跑不起来的 SQL", () => {
    /*
     * MariaDB 要求 `--` 后面跟空白才算注释。仓库里目前**没有**守这条的闸
     * （记忆里那条 sql-comment-needs-space 至今只是一条经验），
     * 所以这里更不能顺手把它当注释盖掉 —— 那会让两个缺陷互相掩护。
     */
    expect(maskSqlNoise(`--别用${BAD}`)).toContain(BAD);
  });
});
