// 端上契约的读取层：**只读端真正会用的两份声明**，不重写一份。
//
//   endpoints.ts    —— 端会打哪条路径（纯数据，Node 可直接正则取）
//   openapi-*.yaml  —— 端以为响应长什么样（由端上的 TS 类型生成）
//
// 这是 E2E-2 与 E2E-1 的分水岭：E2E-1 用 Java 类型验后端自洽，
// 这里用**端上的类型**验「端能不能真的跑」——
// 「ops-web 写了 auditStatus 而后端给的是 status」那类错配，只有这一层看得见。
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

/**
 * 读端点表。
 *
 * 用正则而不是 import：`endpoints.ts` 是 TS，Node 直接跑不了；
 * 而它是**纯数据**（没有表达式、没有引用），正则取是安全的 ——
 * 与 `scripts/gen-delivery-status.mjs` 同一套做法，两处口径必须一致。
 */
export function loadEndpoints(app) {
  const src = fs.readFileSync(path.join(ROOT, app, "src/api/endpoints.ts"), "utf8");
  const out = {};
  const re =
    /(\w+):\s*\{\s*method:\s*"(GET|POST)",\s*\n?\s*path:\s*"([^"]+)",\s*auth:\s*(true|false)/g;
  for (const m of src.matchAll(re)) {
    out[m[1]] = { key: m[1], method: m[2], path: m[3], auth: m[4] === "true", app };
  }
  if (!Object.keys(out).length) {
    throw new Error(`${app}/src/api/endpoints.ts 一条都没解析到 —— 表结构变了？`);
  }
  return out;
}

/**
 * 极简 YAML 读取：只取我们要的两段（paths 里的 operationId→data 的 $ref、components.schemas）。
 *
 * 不引 yaml 依赖，是因为这两份文件是**我们自己生成的**，格式固定（两空格缩进、值都带引号）。
 * 真要换成通用 YAML 解析器时，这里的假设会在第一次解析失败时立刻暴露，而不是悄悄给出错结果。
 */
export function loadOpenapi(file) {
  const text = fs.readFileSync(path.join(ROOT, "docs/api", file), "utf8");

  // ① operationId → data 的 schema 名
  const dataRefOf = {};
  let currentOp = null;
  for (const line of text.split("\n")) {
    const op = line.match(/^\s+operationId:\s*"(\w+)"/);
    if (op) {
      currentOp = op[1];
      continue;
    }
    const ref = line.match(/"\$ref":\s*"#\/components\/schemas\/([\w.\-]+)"/);
    if (ref && currentOp && !(currentOp in dataRefOf)) {
      dataRefOf[currentOp] = ref[1];
    }
  }

  // ② components.schemas：每个类型的 properties 与 required
  const schemas = {};
  const lines = text.split("\n");
  const start = lines.findIndex((l) => /^\s{2}schemas:\s*$/.test(l));
  if (start < 0) {
    throw new Error(`${file} 里没有 components.schemas —— 生成器换格式了？`);
  }
  let name = null;
  let inProps = false;
  let inRequired = false;
  for (let i = start + 1; i < lines.length; i += 1) {
    const l = lines[i];
    const decl = l.match(/^\s{4}"?([\w.\-]+)"?:\s*$/);
    if (decl) {
      name = decl[1];
      schemas[name] = { properties: [], required: [] };
      inProps = false;
      inRequired = false;
      continue;
    }
    if (!name) continue;
    if (/^\s{6}properties:\s*$/.test(l)) { inProps = true; inRequired = false; continue; }
    if (/^\s{6}required:\s*$/.test(l)) { inRequired = true; inProps = false; continue; }
    if (/^\s{6}\w/.test(l)) { inProps = false; inRequired = false; }
    if (inProps) {
      const p = l.match(/^\s{8}"?([\w.\-]+)"?:\s*$/);
      if (p) schemas[name].properties.push(p[1]);
    }
    if (inRequired) {
      const r = l.match(/^\s{8}-\s*"?([\w.\-]+)"?\s*$/);
      if (r) schemas[name].required.push(r[1]);
    }
  }
  return { dataRefOf, schemas };
}

/**
 * 校验一个响应体是否符合端上声明的形状。
 *
 * **只查「端要的字段在不在」，不查「后端多给了什么」**：
 * 多给字段对端是无害的（TS 结构类型会忽略），而少给一个必填字段
 * 端上就是 `undefined` —— 页面渲染出空白，且不报错。
 *
 * @returns 缺失的字段路径数组；空数组 = 通过
 */
export function checkShape(spec, schemaName, data, depth = 0) {
  const schema = spec.schemas[schemaName];
  if (!schema || depth > 3) return [];
  if (data === null || data === undefined) return [`${schemaName}（整个 data 为空）`];

  // 数组响应：抽第一个元素验；空数组说明这次跑到的数据不够，不是契约问题
  const sample = Array.isArray(data) ? data[0] : data;
  if (!sample || typeof sample !== "object") return [];

  const missing = [];
  for (const field of schema.required) {
    if (!(field in sample)) missing.push(`${schemaName}.${field}`);
  }
  return missing;
}
