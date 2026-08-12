// 从 ops-web 的契约生成 OpenAPI 3.1，输出到 docs/api/openapi-ops.yaml。
//
// 为什么值得做这一步：
//   后端还不存在。等它开写时，最省事的路径是**照着契约生成 controller 骨架**，
//   而不是让人对着前端代码手抄 131 个端点 —— 手抄必然漂移，且漂移只在联调时才发现。
//
// 数据流：
//   lib/api/https/*.ts        端点（method + path，从 client.get/post 调用里解析）
//   lib/api/contracts/*.ts    方法名 + JSDoc（作为 summary）
//   lib/types/index.ts        契约类型 → ts-json-schema-generator → components.schemas
//        ↓
//   docs/api/openapi-ops.yaml
//
// ⚠️ 本脚本**会因为契约与端点对不上而失败**（而不是静默少生成几个）：
//   契约里声明了方法却没有对应的 client 调用，说明 http 实现漏了；反过来说明契约漏了。
//   这两种情况都必须在生成时就炸出来 —— 生成一份"少了几个端点"的规格比不生成更糟，
//   因为后端会照着它写，然后在联调时发现缺接口。
//
// 运行：npm run gen:api
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createGenerator } from "ts-json-schema-generator";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const outFile = path.resolve(root, "../docs/api/openapi-ops.yaml");

// ─────────────────────────────────────────────── 1. 端点：解析 https/*.ts
//
// 切片格式高度统一（`name: (args) => client.get("/ops/x", q)`），用正则足够；
// 模板串与普通串都要认（路径参数写成 `${no}`）。
const httpsDir = path.resolve(root, "lib/api/https");
const endpoints = {};        // methodName -> { httpMethod, path }
const duplicates = [];

for (const file of fs.readdirSync(httpsDir).filter((f) => f.endsWith(".ts"))) {
  const src = fs.readFileSync(path.join(httpsDir, file), "utf8");
  const domain = file.replace(/\.ts$/, "");
  // 形如：  listMerchants: (q) => client.get("/ops/merchants", q),
  //        setStatus: (no, s) =>\n    client.post(`/ops/merchants/${no}/status`, {...}),
  //
  // **也要认 async + 块体**：
  //        login: async (u, p) => {\n  const raw = await client.post("/ops/auth/login", …)
  // 只认单表达式箭头函数的话，需要在 http 层做响应映射的端点会**整条从契约里消失** ——
  // 而那正是最需要被记进契约的一类（形状两端不同才要映射）。login 就这么漏过一次。
  const re = /(\w+):\s*(?:async\s*)?\([^)]*\)\s*=>\s*(?:\{[\s\S]{0,400}?)?(?:[\w$.]+\(\s*)*(?:await\s+)?client\.(get|post|put)(?:<[^(\n]*>)?\(\s*([`"])([^`"]+)\3/g;
  let m;
  while ((m = re.exec(src))) {
    const [, name, httpMethod, , rawPath] = m;
    if (endpoints[name]) duplicates.push(name);
    endpoints[name] = {
      httpMethod: httpMethod.toUpperCase(),
      // `${merchantNo}` → {merchantNo}：OpenAPI 的路径参数写法。
      // **也要认 `${v.asNo}` 这种带前缀的**：只匹配裸标识符时，10 条路径原样带着
      // `${v.xxx}` 进了契约 —— 后端永远匹配不上，它们会被守卫永远算成「前端独有」。
      path: rawPath.replace(/\$\{[^}]*?(\w+)\}/g, "{$1}"),
      domain,
    };
  }
}

if (duplicates.length) {
  console.error(`✗ 端点方法名重复（契约是扁平合并的，同名会静默覆盖）：${duplicates.join(", ")}`);
  process.exit(1);
}

// ─────────────────────────────────────────────── 2. 契约：方法名 + JSDoc summary
const contractsDir = path.resolve(root, "lib/api/contracts");
const contractMethods = {};  // methodName -> summary
const returnTypes = {};      // methodName -> 契约签名里的 TS 返回类型

for (const file of fs.readdirSync(contractsDir).filter((f) => f.endsWith(".ts"))) {
  const src = fs.readFileSync(path.join(contractsDir, file), "utf8");

  // 返回类型：签名里本来就写着，只是一直没人取 —— 于是 151 条操作的响应统统是
  // `Result<unknown>`，这份契约退化成一张路径表。后端拿它写不出任何 DTO，
  // 而响应描述里那句「data 的结构见对应契约方法的 TS 返回类型」，
  // 等于把问题原样推回源码：要读 TS 才能实现的东西，就不叫契约。
  // 单独一遍全文匹配（签名常跨行），不掺进下面的 JSDoc 状态机。
  // 参数里排除 `;` 是错的：入参常是对象字面量 `v: { no: string; reason: string }`，
  // 里面本来就有分号，于是 21 个方法被截断、悄悄退回 unknown。用惰性 `.*?`，
  // 让它停在第一个 `): Promise<` 上。
  for (const m of src.matchAll(/^ {2}(\w+)\((.*?)\):\s*Promise<(.+?)>;\s*$/gms)) {
    returnTypes[m[1]] = m[3].trim();
  }

  //
  // 逐行扫描，而不是用一个「可选 JSDoc + 方法签名」的大正则。
  // 踩过：那种写法里可选的 JSDoc 组会贪婪地跨过**没有注释的方法**
  // （`getFastRefundRule` 就是这么被吞掉的），而且是静默吞掉 —— 幸好有一致性校验兜住。
  //
  // 状态机很简单：见到 /** 进入注释缓冲，见到 */ 收尾，见到方法签名就消费缓冲。
  let docBuf = [];
  let inDoc = false;
  for (const line of src.split("\n")) {
    if (/^\s*\/\*\*/.test(line)) { inDoc = true; docBuf = []; }
    if (inDoc) {
      docBuf.push(line);
      if (/\*\//.test(line)) inDoc = false;
      continue;
    }
    // 接口体内的方法签名：两个空格缩进 + 名字 + 左括号
    const m = /^ {2}(\w+)\(/.exec(line);
    if (!m) {
      // 非签名行（空行、import、interface 声明…）会把缓冲冲掉：
      // JSDoc 只归属**紧跟其后**的方法
      if (line.trim() && !/^\s*[}\]]/.test(line)) docBuf = [];
      continue;
    }
    const name = m[1];
    const summary = docBuf
      .map((l) => l.replace(/^\s*\/?\*+\/?\s?/, "").trim())
      .filter((l) => l && !l.startsWith("@") && !l.startsWith("⚠️"))
      .join(" ")
      .split(/[。；]/)[0]
      .trim();
    contractMethods[name] = summary || name;
    docBuf = [];
  }
}

// ─────────────────────────────────────────────── 3. 契约 ↔ 端点 一致性（会失败）
const contractNames = Object.keys(contractMethods);
const endpointNames = Object.keys(endpoints);
const missingImpl = contractNames.filter((n) => !endpoints[n]);
const orphanImpl = endpointNames.filter((n) => !contractMethods[n]);

if (missingImpl.length || orphanImpl.length) {
  if (missingImpl.length) console.error(`✗ 契约声明了但 http 实现里没有的方法：\n  ${missingImpl.join("\n  ")}`);
  if (orphanImpl.length) console.error(`✗ http 实现里有但契约没声明的方法：\n  ${orphanImpl.join("\n  ")}`);
  console.error("\n生成一份「少了几个端点」的规格比不生成更糟 —— 后端会照着它写，联调时才发现缺接口。");
  process.exit(1);
}

// ─────────────────────────────────────────────── 4. 类型 → JSON Schema
const gen = createGenerator({
  path: path.resolve(root, "lib/types/index.ts"),
  tsconfig: path.resolve(root, "tsconfig.json"),
  type: "*",
  skipTypeCheck: true,
  expose: "all",
  topRef: true,
  additionalProperties: false,
});
const schema = gen.createSchema("*");
const schemas = schema.definitions ?? {};

// ─────────────────────────────────────────────── 5. 组装 OpenAPI
/** 路径里的 {param} → parameters 定义。全部按 string 处理（业务单号都是字符串）。 */
function pathParams(p) {
  return [...p.matchAll(/\{(\w+)\}/g)].map(([, name]) => ({
    name, in: "path", required: true, schema: { type: "string" },
  }));
}

/**
 * 响应体：把契约签名里的返回类型接到 components.schemas 上。
 *
 * schemas 早就全量生成好了（第 4 步 `type: "*"`），缺的只是这一步接线。
 * 三种形状分开处理，因为它们在 JSON 里长得不一样：
 *   `Page<X>` → { records: X[], total, page, size }（分页口径，见 info.description）
 *   `X[]`     → 数组
 *   `X`       → 直接 $ref
 * 名字在 schemas 里找不到时**保留 unknown 而不是硬编一个 $ref** —— 指向不存在的
 * schema 会让整份 spec 变成无效的 OpenAPI，比不带类型更坏。
 */
function dataSchema(name) {
  const t = returnTypes[name];
  if (!t || t === "void") return { type: "object", nullable: true };
  const ref = (x) => (schemas[x] ? { $ref: `#/components/schemas/${x}` } : { type: "object" });
  const page = t.match(/^Page<(\w+)>$/);
  if (page) {
    return {
      type: "object",
      properties: {
        records: { type: "array", items: ref(page[1]) },
        total: { type: "integer" },
        page: { type: "integer" },
        size: { type: "integer" },
      },
      required: ["records", "total", "page", "size"],
    };
  }
  const arr = t.match(/^(\w+)\[\]$/);
  if (arr) return { type: "array", items: ref(arr[1]) };
  if (/^(string|number|boolean)$/.test(t)) return { type: t === "number" ? "integer" : t };
  return ref(t);
}

function okResponse(name) {
  return {
    description: "成功",
    content: {
      "application/json": {
        schema: {
          type: "object",
          properties: {
            code: { type: "integer", description: "0 表示成功" },
            msg: { type: "string" },
            data: dataSchema(name),
          },
          required: ["code", "msg", "data"],
        },
      },
    },
  };
}

const paths = {};
for (const [name, ep] of Object.entries(endpoints)) {
  const item = (paths[ep.path] ??= {});
  const op = {
    operationId: name,
    summary: contractMethods[name],
    tags: [ep.domain],
    responses: { 200: okResponse(name) },
  };
  const params = pathParams(ep.path);
  if (params.length) op.parameters = params;
  if (ep.httpMethod === "GET") {
    // 列表类的查询参数是 PageQ 及其派生，形状见 lib/api/query.ts；
    // 这里不逐个展开（它们是索引签名类型，展开后是一堆 additionalProperties）
    op.description = "查询参数见 lib/api/query.ts 中对应的 *Q 类型。";
  } else {
    op.requestBody = {
      required: true,
      content: { "application/json": { schema: { type: "object" } } },
      description: "请求体字段见对应契约方法的入参。",
    };
  }
  item[ep.httpMethod.toLowerCase()] = op;
}

const doc = {
  openapi: "3.1.0",
  info: {
    title: "ai-shop 平台运营端（ops-web）",
    version: "0.1.0",
    description: [
      "由 ops-web 的契约自动生成，请勿手改（cd ops-web && npm run gen:api）。",
      "",
      "口径：响应包 {code,msg,data}，分页 {records,total,page,size}，camelCase，",
      "业务单号 xxxNo，时间 xxxAt，枚举大写下划线，金额为最小货币单位整数。",
      "禁止 delete*，软删除用 archive*/unarchive*。",
      "",
      "鉴权：STAFF 池 Bearer + RBAC + 数据域（矩阵 §2.3）。",
      "越权拦截以后端为准 —— 前端只做展示裁剪。",
    ].join("\n"),
  },
  servers: [{ url: "http://localhost:8080", description: "本地后端" }],
  components: {
    securitySchemes: {
      bearerAuth: { type: "http", scheme: "bearer", description: "STAFF 池 Bearer，含角色与数据域声明" },
    },
    schemas,
  },
  security: [{ bearerAuth: [] }],
  paths,
};

// ─────────────────────────────────────────────── 6. 输出 YAML
//
// 键全部加引号，避免 YAML 的隐式类型转换坑（`on:` 会被读成 boolean 那类）。
//
// ⚠️ 数组里放对象时**不能**简单地 `- ` + 递归结果：对象的第一个键要跟在 `- ` 后面同一行，
//   其余键与它对齐。第一版写成 `- \n  key: ...`，产出的是非法 YAML —— 生成器自己
//   报了「✓ 成功」，但文件根本解析不了。所以下面用 js-yaml 之外的手写实现时，
//   必须配一个解析校验（见 §7），否则"成功"是假的。
/**
 * 安全的裸 key：字母数字下划线连字符点，且不以数字开头。其余（含 `/ops/x` 这类路径）加引号。
 *
 * 为什么不无条件加引号：那样连 `paths:` 都会写成 `"paths":` —— 合法 YAML，但没人这么写，
 * 下游按 `^paths:` 匹配的工具**一条也读不到**。C 端生成器就这么把
 * `backend/scripts/api-align.py` 静默瘸掉过：守卫报「契约 0 条、覆盖率 0%」，
 * 而它正是那个专门用来发现漂移的东西。C 端已修，这里是同一个坑的第二处。
 */
const yamlKey = (k) => (/^[A-Za-z_][\w.-]*$/.test(k) ? k : JSON.stringify(k));

function toYaml(v, indent = 0) {
  const pad = "  ".repeat(indent);
  if (v === null || v === undefined) return "null";
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number" || typeof v === "boolean") return String(v);

  if (Array.isArray(v)) {
    if (!v.length) return "[]";
    return "\n" + v.map((x) => {
      if (x !== null && typeof x === "object" && !Array.isArray(x)) {
        // 对象项：首键跟在 "- " 后，其余键缩进对齐
        const keys = Object.keys(x);
        if (!keys.length) return `${pad}- {}`;
        const lines = keys.map((k, i) => {
          /*
           * **indent + 2，不是 +1。**
           *
           * 数组项的键因为 `- ` 前缀已经比 pad 多缩进了一级，所以它的子值要再深一级
           * 才算挂在它下面。写 +1 的话子值与键本身同列 —— 于是
           *
           *     - type: "array"
           *       items:
           *       type: "string"     ← 本该在 items 下，实际成了 items 的兄弟
           *
           * 产出的是**非法 YAML**（`type` 在同一层出现两次）。
           * 而后果不是「文件难看」：`spec-completeness.test.ts` 要 parse 这三份 spec，
           * 撞上重复键时 yaml 不是快速失败而是卡死 —— 症状是「shared 测试跑不完」，
           * 跟契约生成看不出任何关系。2026-08-11 为此排查了很久。
           *
           * 只有嵌套数组（`string[] | null` 这种 anyOf）会触发，所以它藏了很久。
           */
          const body = toYaml(x[k], indent + 2);
          const prefix = i === 0 ? `${pad}- ` : `${pad}  `;
          return `${prefix}${yamlKey(k)}: ${body}`;
        });
        return lines.join("\n");
      }
      return `${pad}- ${toYaml(x, indent + 1).replace(/^\n/, "")}`;
    }).join("\n");
  }

  const keys = Object.keys(v);
  if (!keys.length) return "{}";
  return "\n" + keys.map((k) => `${pad}${yamlKey(k)}: ${toYaml(v[k], indent + 1)}`).join("\n");
}

const yamlText = toYaml(doc).replace(/^\n/, "") + "\n";

// ─────────────────────────────────────────────── 7. 自校验：产物必须能被解析回来
//
// 手写的 YAML emitter 出过一次「输出了但解析不了」—— 那种失败最坏：脚本打印 ✓，
// 后端拿去导入时才发现是坏文件。这里把产物解析回来，与源对象逐键比对。
function parseYamlLoose(text) {
  // 只需要验证结构合法性，不追求完整 YAML 支持：产物是本脚本生成的，形状可控。
  // 用 JSON 往返做等价性检查即可 —— 若能解析成功且键数一致，说明缩进没写错。
  const lines = text.split("\n").filter((l) => l.trim());
  const stack = [{ indent: -1, node: {} }];
  for (const line of lines) {
    const indent = line.match(/^ */)[0].length;
    const trimmed = line.trim();
    while (stack.length > 1 && indent <= stack[stack.length - 1].indent) stack.pop();
    const parent = stack[stack.length - 1].node;
    const isItem = trimmed.startsWith("- ");
    const content = isItem ? trimmed.slice(2) : trimmed;
    // key 两种写法都要认：带引号的（路径、状态码）与裸的（paths、info…）。
    // 只认带引号的话，验证器就把「无条件加引号」这个错误约定固化了 ——
    // 而正是那个约定让下游的契约守卫读出「契约 0 条」（见 yamlKey 的注释）。
    const kv = /^("(?:[^"\\]|\\.)*"|[A-Za-z_][\w.-]*):\s*(.*)$/.exec(content);
    if (!kv) continue;
    const raw = kv[1];
    const key = raw.startsWith('"') ? JSON.parse(raw) : raw;
    const rest = kv[2];
    const container = isItem
      ? (Array.isArray(parent) ? (parent.push({}), parent[parent.length - 1]) : parent)
      : parent;
    if (rest === "") {
      container[key] = {};
      stack.push({ indent, node: container[key] });
    } else if (rest === "[]") {
      container[key] = [];
      stack.push({ indent, node: container[key] });
    } else {
      container[key] = rest;
    }
  }
  return stack[0].node;
}

const reparsed = parseYamlLoose(yamlText);
if (!reparsed.openapi || !reparsed.paths) {
  console.error("✗ 产物无法解析回来（YAML 缩进写坏了）—— 不写入文件");
  process.exit(1);
}

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, yamlText, "utf8");

const pathCount = Object.keys(paths).length;
console.log(`✓ ${outFile}`);
console.log(`  ${endpointNames.length} 个端点 / ${pathCount} 条路径 / ${Object.keys(schemas).length} 个类型定义`);
