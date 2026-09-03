// 契约镜像 —— 与后端 /mp/** 同源。后端 openapi 就绪后改为自动生成。
// 口径：camelCase · 单号 xxxNo · 时间 xxxAt(毫秒时间戳/UTC) · 枚举大写下划线
// 金额：一律「最小货币单位」整数（人民币分 / 美分 / 菲尔），展示时按市场货币格式化
//
// ─────────────────────────────────────────────────────────────────────────────
// 2026-09-03：按域拆开（这里只剩一份门面）
// ─────────────────────────────────────────────────────────────────────────────
// 这个文件曾经是 5139 行、228 个实体：三端共用的整套契约堆在一处，
// 找一个类型靠搜索，改一处不知道会碰到谁 —— 而平台端（`ops-web/lib/types`）
// 早就是按域分文件的。两边同一件事两种组织方式，新人第一次读就要问为什么。
//
// **拆分不改任何一个字**：每个声明连同它的注释整块搬走，这里 `export *` 回来，
// 所以 `@shared/types` 的导入一行都不用改。
//
// ⚠️ 拆之前先修了契约生成器：它原来按名逐个 `createSchema(name)`，
// 解不开跨文件引用又把失败 catch 掉 —— 那时候拆，三份契约会**静默少 39 个 schema**。
// 现在走 `createSchema("*")`（与平台端同一种调法），`spec-schema-ratchet` 盯着数字。
//
// 加新类型时放进它所属的域文件；不确定放哪，多半说明那个类型的归属本身没想清楚。

export * from "./core";
export * from "./fulfillment";
export * from "./inventory";
export * from "./marketing";
export * from "./member";
export * from "./merchant";
export * from "./message";
export * from "./product";
export * from "./region";
export * from "./review";
export * from "./store";
export * from "./trade";
export * from "./user";
