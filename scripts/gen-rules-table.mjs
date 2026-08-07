#!/usr/bin/env node
/**
 * 交易规则表生成器 —— **规则数字的唯一出口**。
 *
 * 为什么要有它：同一个数字（15 分钟关单、¥50 极速退、2% 佣金…）此前散在三份功能清单里，
 * C 端提了 8 条、B 端 2 条、平台端 4 条，谁也不知道谁是权威。
 * 改一次值要找三处文档，漏一处就出现「文档说 15 分钟、代码是 30 分钟」这种没人发现的假文档。
 *
 * 现在：**值只在 `packages/shared/src/utils/constants` 里存在一份**，
 * 本脚本把它渲染进三端矩阵文档的标记之间。三份清单只引用这张表，不复述数字。
 *
 * 用法：npm run gen:rules
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SRC = join(ROOT, "packages/shared/src/utils/constants/index.ts");
const DOC = join(ROOT, "docs/requirements/需求矩阵-三端.md");
const BEGIN = "<!-- RULES:BEGIN 由 scripts/gen-rules-table.mjs 生成，勿手改 -->";
const END = "<!-- RULES:END -->";

/** 每条规则：谁消费它、在哪一端体现 —— 这部分是人写的语义，代码里没有 */
const MEANING = {
  payTimeoutMinutes: ["未支付自动关单", "分钟", "C 下单页倒计时 / P 关单策略"],
  stockLockMinutes: ["提单锁库超时", "分钟", "C 提单 / B 库存"],
  freshCutoffTime: ["生鲜每日截单", "", "C 商品卡倒计时 / B 备货"],
  freshClaimHours: ["坏果包赔时限（自核销起）", "小时", "C 售后入口可见性"],
  instantRefundMaxMinor: ["极速退款自动通过上限", "分", "C 售后提示 / P 阈值配置"],
  pickupGraceDays: ["逾期未自提顺延", "天", "C 取货提醒 / B 自提点清点"],
  groupBuyTimeoutHours: ["拼团超时未成团自动退款", "小时", "C 团购进度 / B 开团"],
  appointmentWindowDays: ["预约可选天数窗口", "天", "C 预约选时"],
  appointmentChangeBeforeHours: ["最晚改期（服务开始前）", "小时", "C 改期按钮"],
  deliveryFeeMinor: ["送货上门费", "分", "C 结算页 / B 配送规则"],
  deliveryFreeThresholdMinor: ["免配送门槛", "分", "C 结算页 / B 配送规则"],
};

const SETTLE_MEANING = {
  "commissionRate.MERCHANT_OWNED": ["自带客流佣金率", "B 结算单 / P 分档费率"],
  "commissionRate.PLATFORM": ["平台客流佣金率", "B 结算单 / P 分档费率"],
  fulfillFeePerItemMinor: ["自提点履约服务费（按件）", "B 结算单 / P 费率配置"],
  periodDays: ["结算周期", "B 结算单 / P 结算跑批"],
};

/** 分 → 元，只用于展示 */
const money = (minor) => `¥${(Number(minor) / 100).toFixed(2)}`;

function grab(src, block) {
  const i = src.indexOf(`export const ${block} = {`);
  if (i < 0) throw new Error(`找不到常量块 ${block}`);
  return src.slice(i, src.indexOf("} as const;", i));
}

const src = readFileSync(SRC, "utf8");
const trade = grab(src, "TRADE_RULES");
const settle = grab(src, "SETTLE");

const rows = [];
for (const [key, [label, unit, consumer]] of Object.entries(MEANING)) {
  const m = trade.match(new RegExp(`${key}:\\s*([^,\\n]+)`));
  if (!m) throw new Error(`TRADE_RULES 里没有 ${key} —— 常量删了就把本表这一行也删掉`);
  const raw = m[1].trim().replace(/"/g, "");
  const shown = unit === "分" ? money(raw) : unit ? `${raw} ${unit}` : raw;
  rows.push(`| \`${key}\` | ${label} | **${shown}** | ${consumer} |`);
}
for (const [key, [label, consumer]] of Object.entries(SETTLE_MEANING)) {
  const leaf = key.split(".").pop();
  const m = settle.match(new RegExp(`${leaf}:\\s*([^,\\n]+)`));
  if (!m) throw new Error(`SETTLE 里没有 ${key}`);
  const raw = m[1].trim();
  const shown = key === "fulfillFeePerItemMinor" ? `${money(raw)}/件`
    : key.startsWith("commissionRate") ? `${(Number(raw) * 100).toFixed(0)}%`
    : `${raw} 天`;
  rows.push(`| \`SETTLE.${key}\` | ${label} | **${shown}** | ${consumer} |`);
}

const table = [
  BEGIN,
  "",
  "| 常量 | 含义 | 值 | 谁消费 |",
  "|---|---|---|---|",
  ...rows,
  "",
  "> 值的**唯一事实源**是 `packages/shared/src/utils/constants`。改值改代码，然后 `npm run gen:rules`。",
  "> 三份功能清单只引用这张表，不复述数字 —— 复述过一次就会有一处忘了改。",
  "",
  END,
].join("\n");

const doc = readFileSync(DOC, "utf8");
const a = doc.indexOf(BEGIN);
const b = doc.indexOf(END);
if (a < 0 || b < 0) throw new Error("矩阵文档里找不到 RULES 标记");
writeFileSync(DOC, doc.slice(0, a) + table + doc.slice(b + END.length));
console.log(`已写入 ${rows.length} 条规则到 需求矩阵-三端.md`);
