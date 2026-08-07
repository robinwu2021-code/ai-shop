#!/usr/bin/env node
/**
 * API 详情生成器 —— 字段级的接口文档，供后端照着实现、供评审逐条核对。
 *
 * 与 `gen-api-index.mjs` 的分工：
 *   索引（API清单.md）  一行一个接口，回答「有哪些接口、谁在用、做了没有」
 *   详情（本文件）      一段一个接口，回答「入参出参到底有哪些字段、什么类型、必填吗、什么意思」
 *
 * 三份 OpenAPI 是形状真源，这里只做**渲染**，不做推断 ——
 * 文档里出现的每个字段、每条说明都能在 spec 里找到出处，spec 里的每条说明又来自
 * TS 类型上的 JSDoc。没有说明的字段会被如实标成空，并在文末统计出来：
 * 与其编一句话糊上去，不如让「这个字段没人解释过」这件事本身可见。
 *
 * 用法：npm run gen:api-detail
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const DOMAINS = [
  { label: "C 端", file: "docs/api/openapi.yaml", out: "docs/api/API详情-C端.md", who: "c-app（消费者小程序 / H5）", prefix: "/mp" },
  { label: "B 端", file: "docs/api/openapi-b.yaml", out: "docs/api/API详情-B端.md", who: "b-app（商家端）", prefix: "/biz" },
  { label: "平台端", file: "docs/api/openapi-ops.yaml", out: "docs/api/API详情-平台端.md", who: "ops-web（运营后台）", prefix: "/ops" },
];

/** `#/components/schemas/Xxx` → `Xxx`；ops 生成器会 URL 编码泛型名（`Page%3CX%3E`） */
const refName = (r) => decodeURIComponent(r.replace("#/components/schemas/", ""));

/** 锚点：GitHub/多数 Markdown 渲染器把标题里的非字母数字去掉、空格转连字符 */
const anchor = (name) => `#${name.toLowerCase().replace(/[^\w一-龥]+/g, "")}`;

/**
 * 类型的可读写法。引用类型渲染成指向「数据模型」章节的链接 ——
 * 字段表里写一个光秃秃的 `OrderAmount` 等于让人自己去全文搜。
 */
function typeName(s, { link = true } = {}) {
  if (!s) return "—";
  if (s.$ref) {
    const n = refName(s.$ref);
    return link ? `[\`${n}\`](${anchor(n)})` : `\`${n}\``;
  }
  if (s.enum) return s.enum.map((v) => `\`${v}\``).join(" \\| ");
  if (s.type === "array") return `${typeName(s.items, { link })}\\[\\]`;
  if (s.anyOf || s.oneOf) {
    return (s.anyOf ?? s.oneOf).map((x) => typeName(x, { link })).join(" \\| ");
  }
  if (s.type === "object" && s.properties) return "`object`（见下）";
  return `\`${s.type ?? "any"}\``;
}

/** 表格里的说明：换行会撑破 Markdown 表格，竖线会被当成列分隔 */
const cell = (t) => (t ?? "").replace(/\|/g, "\\|").replace(/\n+/g, " ").trim();

/**
 * 对象的字段表。只展开一层：再往下是引用类型，去「数据模型」里看 ——
 * 就地无限展开会让同一个 Order 在文档里出现几十遍，且改一处要改几十处。
 */
function fieldTable(schema, { prefix = "" } = {}) {
  const req = new Set(schema.required ?? []);
  const props = Object.entries(schema.properties ?? {});
  if (!props.length) return ["_无字段_", ""];
  const rows = ["| 字段 | 类型 | 必填 | 说明 |", "|---|---|:---:|---|"];
  // 匿名内联对象（`items: { goodsNo: string; qty: number }[]` 那种）在表里只能写成
  // 「object（见下）」—— 它没有类型名，去不了「数据模型」章节。不接着展开的话，
  // 下单接口最要紧的那几个字段就是**看不见**的。收集起来，在主表后逐个补表。
  const nested = [];
  for (const [name, s] of props) {
    const inline = s.type === "array" ? s.items : s;
    if (inline?.type === "object" && inline.properties) {
      nested.push([`${prefix}${name}${s.type === "array" ? "[]" : ""}`, inline]);
    }
    rows.push(
      `| \`${name}\` | ${typeName(s)} | ${req.has(name) ? "是" : "否"} | ${cell(s.description) || "—"} |`,
    );
  }
  rows.push("");
  for (const [path, sub] of nested) {
    rows.push(`\`${path}\` 的字段：`, "", ...fieldTable(sub, { prefix: `${path}.` }));
  }
  return rows;
}

/**
 * 参数释义。
 *
 * OpenAPI 里的路径参数只有名字和类型，没有说明 —— 于是「入参」表里那一列整片是空的，
 * 而路径参数往往是这个接口最要紧的入参（要操作哪一单）。
 *
 * 这不是逐接口推断语义，而是全站**命名约定**的字典：`xxxNo` 是业务单号，
 * 全站同名同义（口径见文首「通用约定」）。字典里没有的照旧留空，不编。
 */
const PARAM_DESC = {
  addressId: "地址簿记录 ID（非业务单号，不进订单快照）",
  afterSaleNo: "售后单号",
  asNo: "售后单号（平台端写法）",
  auditNo: "审核单号",
  batchNo: "到货批次号",
  campaignNo: "活动单号",
  cardNo: "卡号 / 会员卡单号",
  carrier: "承运商标识",
  code: "取货码 / 核销码",
  couponNo: "券单号",
  demandNo: "求团需求单号",
  diffNo: "对账差异单号",
  goodsNo: "商品单号",
  groupNo: "团单号",
  invoiceNo: "开票申请单号",
  key: "开关标识（FeatureFlag.key）",
  merchantNo: "商家单号",
  messageNo: "站内消息单号",
  no: "该资源的业务单号",
  orderNo: "订单单号（按商家拆单后的子订单）",
  parentNo: "父单号（同一次结算拆出的子订单共享）",
  postNo: "种草内容单号",
  questionNo: "商品问答单号",
  rankNo: "榜单单号",
  requestNo: "求团需求单号",
  reviewNo: "评价单号",
  role: "角色码",
  shipmentNo: "运单记录单号（平台侧主键，非快递单号）",
  templateNo: "模板单号",
  type: "类型筛选，取值见对应枚举",
  withdrawNo: "提现单号",
  categoryNo: "类目单号",
  categoryType: "品类形态",
  communityNo: "社区单号",
  keyword: "搜索关键词",
  lat: "纬度",
  lng: "经度",
  page: "页码，从 1 起",
  pickupNo: "自提点单号",
  size: "每页条数",
  status: "状态筛选，取值见对应枚举",
};

/** 响应包固定是 `{code,msg,data}`，逐个接口重复三行没有意义，只渲染 data */
function unwrapData(resp) {
  const s = resp?.content?.["application/json"]?.schema;
  if (!s) return null;
  if (s.$ref) return s; // ops 早期写法：整包一个 $ref
  return s.properties?.data ?? s;
}

/** 统计：有多少字段没人写过说明。见文件头 —— 这件事要可见，不要被糊上 */
const stat = { fields: 0, documented: 0, mapped: 0, undoc: [] };

/**
 * 映射类型（`Record<Lang, string>`、`Partial<Record<CurrencyCode, number>>`）的键是
 * **由类型参数生成的**，源码里没有可以挂 JSDoc 的地方。把它们算进「没人写说明」
 * 会让覆盖率永远差最后几个点，而那几个点谁也补不上 —— 指标一旦不可能达成就没人再看。
 * 它们的语义由类型本身说明（见「数据模型」里该条目的类型描述）。
 */
const MAPPED_TYPE = /^(Partial[_<])?Record[_<]/;

function countDoc(schemaName, schema) {
  if (MAPPED_TYPE.test(schemaName)) {
    stat.mapped++;
    return;
  }
  for (const [name, s] of Object.entries(schema.properties ?? {})) {
    stat.fields++;
    if (s.description) stat.documented++;
    else stat.undoc.push(`${schemaName}.${name}`);
  }
}

for (const d of DOMAINS) {
  const file = join(ROOT, d.file);
  if (!existsSync(file)) continue;
  const spec = YAML.parse(readFileSync(file, "utf8"));
  const schemas = spec.components?.schemas ?? {};

  // 只写文档里真正会被引用到的模型，避免把 143 个类型全倒出来
  const used = new Set();
  const markUsed = (s, depth = 0) => {
    if (!s || depth > 6) return;
    if (s.$ref) {
      const n = refName(s.$ref);
      if (!used.has(n)) {
        used.add(n);
        markUsed(schemas[n], depth + 1);
      }
      return;
    }
    if (s.items) markUsed(s.items, depth);
    for (const p of Object.values(s.properties ?? {})) markUsed(p, depth);
    for (const p of s.anyOf ?? s.oneOf ?? []) markUsed(p, depth);
  };

  const ops = [];
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    for (const [method, op] of Object.entries(item)) {
      if (!["get", "post", "put", "delete"].includes(method)) continue;
      ops.push({ path, method: method.toUpperCase(), ...op });
    }
  }
  ops.sort((a, b) => (a.tags?.[0] ?? "").localeCompare(b.tags?.[0] ?? "") || a.path.localeCompare(b.path));

  const md = [
    `# ${d.label} API 详情 · ${d.who}`,
    "",
    "> 由 `npm run gen:api-detail` 从 OpenAPI 生成，**请勿手改**。",
    `> 契约源：[\`${d.file.split("/").pop()}\`](${d.file.split("/").pop()})　总表：[API 清单](API清单.md)`,
    "",
    "## 通用约定",
    "",
    "| 项 | 约定 |",
    "|---|---|",
    "| 响应包 | `{ code, msg, data }`，`code=0` 表示成功；下文「出参」只描述 `data` |",
    "| 分页 | 入参 `page`（从 1 起）、`size`；出参 `{ records, total, page, size }` |",
    "| 金额 | 一律**最小货币单位整数**（分），字段名以 `Minor` 结尾。禁止浮点 |",
    "| 时间 | 毫秒时间戳整数，字段名以 `At` 结尾 |",
    "| 业务单号 | 字符串，字段名以 `No` 结尾（`orderNo`/`goodsNo`…），非自增 ID |",
    "| 枚举 | 大写下划线常量；取值见「数据模型」对应条目 |",
    "| 命名 | camelCase |",
    "| 鉴权 | 🔒 = 需 Bearer token；越权拦截以后端为准，前端仅做展示裁剪 |",
    "",
    "完整口径（错误码分段、HTTP 状态码取舍、空值语义、幂等）见 [响应格式规范](响应格式规范.md)。",
    "",
    "---",
    "",
    "## 接口",
    "",
  ];

  let currentTag = null;
  for (const op of ops) {
    const tag = op.tags?.[0] ?? "其他";
    if (tag !== currentTag) {
      currentTag = tag;
      md.push(`### ${tag}`, "");
    }
    md.push(`#### ${op.method} \`${op.path}\``, "");
    md.push(`${op.summary || "—"}${op.security ? "　🔒" : ""}`, "");
    if (op.description) md.push(`> ${cell(op.description)}`, "");

    // ── 入参：路径参数 + 查询参数 + 请求体
    const params = op.parameters ?? [];
    const body = op.requestBody?.content?.["application/json"]?.schema;
    if (params.length || body) {
      md.push("**入参**", "");
      if (params.length) {
        md.push("| 参数 | 位置 | 类型 | 必填 | 说明 |", "|---|---|---|:---:|---|");
        for (const p of params) {
          md.push(
            `| \`${p.name}\` | ${p.in} | ${typeName(p.schema)} | ${p.required ? "是" : "否"} | ${cell(p.description) || PARAM_DESC[p.name] || "—"} |`,
          );
        }
        md.push("");
      }
      if (body) {
        markUsed(body);
        if (body.$ref) {
          const n = refName(body.$ref);
          md.push(`请求体：${typeName(body)}`, "");
          if (schemas[n]) md.push(...fieldTable(schemas[n]));
        } else {
          md.push(...fieldTable(body));
        }
      }
    } else {
      md.push("**入参**：无", "");
    }

    // ── 出参
    const data = unwrapData(op.responses?.["200"] ?? op.responses?.[200]);
    md.push("**出参**（`data`）", "");
    if (!data) {
      md.push("无", "");
    } else {
      markUsed(data);
      md.push(`类型：${typeName(data)}`, "");
      if (data.$ref && schemas[refName(data.$ref)]) {
        md.push(...fieldTable(schemas[refName(data.$ref)]));
      } else if (data.properties) {
        md.push(...fieldTable(data));
      }
    }
    md.push("");
  }

  // ── 数据模型
  md.push("---", "", "## 数据模型", "");
  for (const name of [...used].sort()) {
    const s = schemas[name];
    if (!s) continue;
    md.push(`### ${name}`, "");
    if (s.description) md.push(cell(s.description), "");
    if (s.enum) {
      md.push("枚举取值：", "", ...s.enum.map((v) => `- \`${v}\``), "");
    } else if (s.properties) {
      countDoc(name, s);
      md.push(...fieldTable(s));
    } else {
      md.push(`类型：${typeName(s, { link: false })}`, "");
    }
  }

  writeFileSync(join(ROOT, d.out), md.join("\n"));
  console.log(`✅ ${d.out}　${ops.length} 个接口 / ${used.size} 个模型`);
}

const pct = ((stat.documented / stat.fields) * 100).toFixed(0);
console.log(`\n字段说明覆盖率 ${stat.documented}/${stat.fields}（${pct}%）　映射类型 ${stat.mapped} 个（键由类型参数生成，无处挂注释）`);
if (stat.undoc.length) {
  console.log(`未写说明的字段 ${stat.undoc.length} 个，前 20：`);
  console.log("   " + stat.undoc.slice(0, 20).join(", "));
  console.log("   （说明来自 TS 类型上的 JSDoc —— 在类型定义处补注释，这里会自动带出来）");
}
