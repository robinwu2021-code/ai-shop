// 通道主体码：**只能是通道文档里真实存在的值**。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么这条要机器判
// ─────────────────────────────────────────────────────────────────────────────
// 2026-08-13 核对微信支付合作伙伴文档中心时发现，种子里两处都是错的：
//
//   · 个体工商户 wechat_code = 'SMALL' —— **取值域里根本没有这个值**
//   · 自然人 settle_account_type = 'PERSONAL_OPENID' —— 描述的机制不存在，
//     微信结算给小微的是**个人银行卡**（bank_account_type=BANK_ACCOUNT_TYPE_PERSONAL
//     + account_name/account_bank/account_number），openid 只是超管签约用的
//
// 两处都**不会在开发期报任何错**：库里存的是字符串，网关还没写，测试全绿。
// 它们只在**真实进件那一刻**暴露，而报错是「主体类型与资料不符」——
// 排查的人会先怀疑上传的资料，不会怀疑映射表。等真到那一步，
// 已经是在联调现场、对着一个不指向根因的报错。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么读 schema-test.sql 而不是 V2 种子
// ─────────────────────────────────────────────────────────────────────────────
// V2 是**已应用**的迁移，不能改（改了 Flyway 校验和失配，共享库起不来 ——
// 本轮真踩到过）。所以 V2 里至今还写着 'SMALL'，修正在 V93。
// 判「现在到底是什么」必须看**重放完所有 UPDATE 之后**的状态，
// 而 schema-test.sql 正是那份线性重放脚本。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const SCHEMA = "backend/shop-app/src/test/resources/schema-test.sql";

/** 微信 subject_type 去前缀后的合法短码。空值合法 —— 表示微信不收这种主体 */
const WECHAT_SUBJECT_CODES = new Set([
  "INDIVIDUAL", // 个体户（特约商户进件）
  "ENTERPRISE", // 企业（特约商户进件）
  "GOVERNMENT", // 政府机关（特约商户进件）
  "INSTITUTIONS", // 事业单位（特约商户进件）
  "OTHERS", // 社会组织（特约商户进件）
  "MICRO", // 小微（**小微商户进件**，另一个接口）
]);

/** 结算账户形态。这不是通道枚举，是本仓的抽象 —— 但每个值都要对应一种真实存在的到账方式 */
const SETTLE_ACCOUNT_TYPES = new Set([
  "PERSONAL_BANK_CARD", // 个人银行卡（自然人/小微）
  "MERCHANT_ID", // 对公账户（个体户/企业）
]);

type Row = { legalForm: string; wechatCode: string; settleAccountType: string; remark: string };

/** V1 定义的列序 */
const COL = { legalForm: 1, wechatCode: 6, settleAccountType: 9, remark: 11 } as const;

/**
 * 线性重放 schema-test.sql 里对 sys_legal_form 的写入，返回最终状态。
 *
 * 只认这份脚本实际用到的两种形态：`INSERT INTO ... VALUES (...),(...)`
 * 与 `UPDATE sys_legal_form SET col = 'v' [, ...] WHERE legal_form = 'X'`。
 * **认不出的语句会被跳过** —— 所以下面第一条断言先确认「确实读到了三档」，
 * 不然解析器一旦失效，后面每条都恒真。
 */
function finalState(): Map<string, Row> {
  const src = readFileSync(join(ROOT, SCHEMA), "utf8");
  const state = new Map<string, Row>();

  const insert = src.match(/INSERT INTO sys_legal_form VALUES([\s\S]*?);/)?.[1];
  if (insert) {
    for (const m of insert.matchAll(/\(([^()]*)\)/g)) {
      const f = m[1].split(",").map((v) => v.trim().replace(/^'|'$/g, ""));
      state.set(f[COL.legalForm], {
        legalForm: f[COL.legalForm],
        wechatCode: f[COL.wechatCode],
        settleAccountType: f[COL.settleAccountType],
        remark: f[COL.remark] ?? "",
      });
    }
  }

  // UPDATE 按文件顺序重放。CONCAT(remark, '...') 只取追加的那段拼上去
  for (const m of src.matchAll(
    /UPDATE sys_legal_form\s+SET ([\s\S]*?)\s+WHERE ([\s\S]*?);/g,
  )) {
    const [, sets, where] = m;
    const target = where.match(/legal_form\s*=\s*'(\w+)'/)?.[1];
    const byOldSettle = where.match(/settle_account_type\s*=\s*'(\w+)'/)?.[1];

    for (const [, r] of state) {
      if (target && r.legalForm !== target) continue;
      if (byOldSettle && r.settleAccountType !== byOldSettle) continue;
      if (!target && !byOldSettle) continue;

      const lf = sets.match(/legal_form\s*=\s*'(\w+)'/)?.[1];
      const wc = sets.match(/wechat_code\s*=\s*'(\w+)'/)?.[1];
      const sa = sets.match(/settle_account_type\s*=\s*'(\w+)'/)?.[1];
      const rk = sets.match(/remark\s*=\s*'([^']*)'/)?.[1];
      const cat = sets.match(/remark\s*=\s*CONCAT\(remark,\s*'([^']*)'\)/)?.[1];
      if (wc) r.wechatCode = wc;
      if (sa) r.settleAccountType = sa;
      if (cat) r.remark += cat;
      else if (rk) r.remark = rk;
      if (lf) {
        state.delete(r.legalForm);
        r.legalForm = lf;
        state.set(lf, r);
      }
    }
  }
  return state;
}

describe("通道主体码", () => {
  const rows = [...finalState().values()];

  it("解析出了三档主体 —— 解析失效的话下面每条断言都恒真", () => {
    expect(rows.map((r) => r.legalForm).sort()).toEqual(
      ["ENTERPRISE", "INDIVIDUAL", "NATURAL_PERSON"].sort(),
    );
  });

  it("★★★ wechat_code 必须是微信 subject_type 里真实存在的值", () => {
    const bad = rows
      .filter((r) => r.wechatCode && r.wechatCode !== "NULL")
      .filter((r) => !WECHAT_SUBJECT_CODES.has(r.wechatCode))
      .map((r) => `${r.legalForm} → '${r.wechatCode}'`);

    expect(
      bad,
      "这些值不在微信 subject_type 的取值域里。\n" +
        "  合法短码（去掉 SUBJECT_TYPE_ 前缀）：" +
        [...WECHAT_SUBJECT_CODES].join(" / ") +
        "\n  错了不会在开发期报错 —— 只在真实进件时报「主体类型与资料不符」，" +
        "而那个报错不指向映射表。\n  " +
        bad.join("\n  "),
    ).toEqual([]);
  });

  it("★★ settle_account_type 必须对应一种真实存在的到账方式", () => {
    const bad = rows
      .filter((r) => r.settleAccountType && r.settleAccountType !== "NULL")
      .filter((r) => !SETTLE_ACCOUNT_TYPES.has(r.settleAccountType))
      .map((r) => `${r.legalForm} → '${r.settleAccountType}'`);

    expect(
      bad,
      "这些到账方式不存在。曾经写过 PERSONAL_OPENID（微信零钱），" +
        "而微信给小微结算的是**个人银行卡** —— \n" +
        "  这个错会一路传到 B 端入驻页，商家据此以为不用提供银行卡。\n  " +
        bad.join("\n  "),
    ).toEqual([]);
  });

  it("★ 自然人的备注要写明它走的是另一个进件接口 —— 这决定了调哪个 URL", () => {
    const np = rows.find((r) => r.legalForm === "NATURAL_PERSON");
    expect(np, "解析不到 NATURAL_PERSON 档").toBeDefined();
    expect(
      np!.remark,
      "自然人走的是【小微商户进件】，与个体户/企业的【特约商户进件】不是同一个接口。" +
        "备注里不写明，写网关的人会照着个体户那条路径调特约商户进件 —— 而那个接口不收小微",
    ).toContain("小微商户进件");
  });
});
