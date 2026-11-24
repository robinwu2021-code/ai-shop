#!/usr/bin/env node
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import YAML from "yaml";
import { buildMatrix, codeFor, permissionAlternatives } from "./gen-perm-endpoint-matrix.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = (file) => readFileSync(root + file, "utf8");
const { eps, codes, matrix } = buildMatrix(root);
const donor = YAML.parse(read("docs/api/openapi-ops.yaml"));
const routeSource = read("backend/shop-gateway/src/main/java/ai/neargo/shop/gateway/ops/OpsApiKeyRoutes.java");
const keyRoutes = new Map([...routeSource.matchAll(/Map\.entry\("([^"]+)", Admission\.(\w+)\)/g)]
  .map(([, operation, access]) => [operation, access]));
const staffKeys = "/ops/staffs/{staffNo}/api-keys";
const ownKeys = "/ops/staffs/me/api-keys";

const string = { type: "string" };
const boolean = { type: "boolean" };
const integer = { type: "integer", format: "int64" };
const nullable = (schema) => ({ ...schema, type: [schema.type, "null"] });
const object = (properties, required = []) => ({ type: "object", properties, required });
const array = (items) => ({ type: "array", items });
const enumString = (...values) => ({ ...string, enum: values });
const ref = (schema) => ({ $ref: `#/components/schemas/${schema}` });
const json = (schema) => ({ "application/json": { schema } });
const pathParam = (name, schema = string) => ({ name, in: "path", required: true, schema });
const queryParam = (name, schema = string) => ({ name, in: "query", schema });
const body = (schema, required = true) => ({ required, content: json(schema) });
const response = (schema) => ({
  description: "统一响应。code=0 表示成功",
  content: json(object({ code: { type: "integer" }, msg: string, data: schema }, ["code", "msg"])),
});
const errors = {
  "401": { description: "凭据无效、到期、已撤销或员工停用", content: json(ref("OpsError")) },
};
const schemas = {
  OpsError: object({ code: { type: "integer", not: { const: 0 } }, msg: string, data: { type: "null" } }, ["code", "msg"]),
  OperatorIdentity: object({
    staffNo: string, username: string, realName: nullable(string), roles: array(string), perms: array(string), status: string,
    merchantNo: nullable(string), communityNo: nullable(string), pickupNo: nullable(string), lastLoginAt: nullable(integer),
    mustChangePassword: boolean, secondFactorExempt: boolean,
  }, ["staffNo", "username", "roles", "perms", "status", "mustChangePassword", "secondFactorExempt"]),
  OpsApiKeyMetadata: object({
    keyNo: { ...string, format: "uuid" },
    label: { ...string, maxLength: 80 },
    access: enumString("READ_ONLY", "MANAGE"),
    issuedBy: string,
    createdAt: { ...integer, description: "Unix 毫秒" },
    expiresAt: { ...integer, description: "Unix 毫秒" },
    revokedAt: { type: ["integer", "null"], description: "Unix 毫秒，未撤销时为空或省略" },
  }, ["keyNo", "label", "access", "issuedBy", "createdAt", "expiresAt"]),
};
const issueRequest = object({
  keyNo: { ...string, format: "uuid", description: "调用方为本次签发生成的 UUID" },
  label: { ...string, minLength: 1, maxLength: 80 },
  access: enumString("READ_ONLY", "MANAGE"),
  validDays: { type: "integer", minimum: 1, maximum: 90 },
}, ["keyNo", "label", "access", "validDays"]);

const existingMetadata = schemas.OpsApiKeyMetadata;
schemas.MyOpsApiKey = object({
  ...existingMetadata.properties,
  status: enumString("ACTIVE", "EXPIRED", "REVOKED"),
}, [...existingMetadata.required, "status"]);
const registerRequest = object({
  ...issueRequest.properties,
  access: { ...issueRequest.properties.access, default: "READ_ONLY" },
  secretHash: { ...string, pattern: "^[a-f0-9]{64}$", writeOnly: true },
}, ["keyNo", "label", "validDays", "secretHash"]);

const sessionOperations = {
  [`GET ${ownKeys}`]: {
    operationId: "listMyApiKeys", summary: "查看本人的 API 密钥",
    responses: { "200": response(array(ref("MyOpsApiKey"))) },
  },
  [`POST ${ownKeys}`]: {
    operationId: "registerMyApiKey", summary: "自助申请并绑定本机 API 密钥",
    requestBody: body(registerRequest), responses: { "200": response(ref("MyOpsApiKey")) },
  },
  [`POST ${ownKeys}/{keyNo}/revoke`]: {
    operationId: "revokeMyApiKey", summary: "撤销本人的 API 密钥",
    parameters: [pathParam("keyNo", { ...string, format: "uuid" })],
    responses: { "200": response({ type: "null" }) },
  },
  [`GET ${staffKeys}`]: {
    operationId: "listStaffApiKeys", summary: "查询员工密钥元数据",
    parameters: [pathParam("staffNo")], responses: { "200": response(array(ref("OpsApiKeyMetadata"))) },
  },
  [`POST ${staffKeys}`]: {
    operationId: "issueStaffApiKey", summary: "为运营员工签发密钥",
    parameters: [pathParam("staffNo")], requestBody: body(issueRequest),
    responses: { "200": {
      ...response(object({ key: ref("OpsApiKeyMetadata"), apiKey: { ...string, readOnly: true,
        description: "密钥明文，仅此次签发返回" } }, ["key", "apiKey"])),
      headers: { "Cache-Control": { schema: { const: "no-store" } } },
    } },
  },
  [`POST ${staffKeys}/{keyNo}/revoke`]: {
    operationId: "revokeStaffApiKey", summary: "撤销员工密钥",
    parameters: [pathParam("staffNo"), pathParam("keyNo", { ...string, format: "uuid" })],
    responses: { "200": response({ type: "null" }) },
  },
};

const pagination = [queryParam("page", { ...integer, default: 1 }),
  queryParam("size", { ...integer, default: 20, maximum: 100 })];
const channelState = object({ storeNo: string, channel: string, locked: boolean }, ["storeNo", "channel", "locked"]);

const fulfillmentChannels = enumString("STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS");
const overrides = {
  "GET /ops/auth/me": {
    summary: "查询当前运营员工身份",
    responses: { "200": response(ref("OperatorIdentity")) },
  },
  "POST /ops/stores/{storeNo}/restore": { requestBody: undefined },
  "GET /ops/stores": {
    parameters: [...["merchantNo", "status", "businessMode", "communityNo", "keyword"].map((name) => queryParam(name)),
      ...pagination],
  },
  "GET /ops/merchants": {
    summary: "检索商户",
    parameters: [...["status", "communityNo", "keyword", "tier"].map((name) => queryParam(name)),
      queryParam("showArchived", boolean), ...pagination],
  },
  "GET /ops/merchants/{merchantNo}": { summary: "查询商户详情" },
  "POST /ops/merchants/{merchantNo}/status": {
    summary: "调整商户经营状态",
    requestBody: body(object({ status: enumString("ACTIVE", "SUSPENDED", "FROZEN"), remark: { ...string, description: "暂停或冻结时必填" } }, ["status"])),
  },
  "POST /ops/stores/{storeNo}/channels/{channel}/lock": {
    summary: "锁定门店履约渠道",
    parameters: [pathParam("storeNo"), pathParam("channel", fulfillmentChannels)],
    requestBody: body(object({ reason: string }), false),
    responses: { "200": response(channelState) },
  },
  "POST /ops/stores/{storeNo}/channels/{channel}/unlock": {
    summary: "解锁门店履约渠道",
    parameters: [pathParam("storeNo"), pathParam("channel", fulfillmentChannels)],
    requestBody: undefined,
    responses: { "200": response(channelState) },
  },
  "GET /ops/service-scopes": { summary: "查询经营范围档位及在用商家数" },
  "GET /ops/industries": { summary: "查询行业主数据和在用商家数" },
  "POST /ops/industries/{industry}/enabled": {
    summary: "调整行业启用状态", requestBody: body(object({ enabled: boolean }, ["enabled"])),
  },
  "POST /ops/service-scopes/{scope}/enabled": {
    summary: "调整经营范围档位", requestBody: body(object({ enabled: boolean, reason: string }, ["enabled", "reason"])),
  },
};

function moduleOf(path) {
  if (path === "/ops/auth/me" || path.startsWith("/ops/staffs/")) return "身份与密钥";
  if (/\/(channels|fulfillment|pay-channels)(\/|$)/.test(path)) return "渠道";
  if (path.startsWith("/ops/stores")) return "门店";
  if (path.startsWith("/ops/merchants")) return "商户";
  return "行业与经营范围";
}

const modules = ["身份与密钥", "门店", "商户", "渠道", "行业与经营范围"];
const admissionLabels = {
  SCOPED_READ: "只读／管理，按员工数据域",
  PLATFORM_READ: "只读／管理，全量数据域",
  PLATFORM_MANAGE: "管理，全量数据域",
  SESSION: "运营会话",
};
const rows = [];
const paths = {};
for (const signature of [...keyRoutes.keys(), ...Object.keys(sessionOperations)]) {
  const [method, path] = signature.split(" ");
  const matches = eps.filter((endpoint) => endpoint.method === method && endpoint.path === path);
  if (matches.length !== 1) throw new Error(`Controller mapping missing or ambiguous: ${signature}`);
  const [endpoint] = matches;
  const access = keyRoutes.get(signature) ?? "SESSION";
  const roles = Object.entries(matrix).filter(([, values]) => values.includes(signature)).map(([role]) => role);
  const permissionCodes = permissionAlternatives(endpoint.perm).map((code) => codeFor(codes, code));
  if (permissionCodes.some((code) => !code) || (permissionCodes.length === 0 && signature !== "GET /ops/auth/me" && !path.startsWith(ownKeys))) {
    throw new Error(`Unknown or missing permission: ${signature}`);
  }
  const name = moduleOf(path);
  const operation = sessionOperations[signature] ?? donor.paths[path]?.[method.toLowerCase()];
  if (!operation) throw new Error(`Missing operation schema: ${signature}`);
  const { description, ...contract } = operation;
  const overridesForOperation = overrides[signature];
  const responses = { ...(overridesForOperation?.responses ?? contract.responses), ...errors };
  const ok = responses["200"];
  const success = structuredClone(ok.content["application/json"].schema);
  success.properties.code.const = 0;
  responses["200"] = {
    ...ok,
    description: "统一响应。code=0 表示成功，其他业务码见 msg",
    content: json({ oneOf: [success, ref("OpsError")] }),
  };
  const spec = {
    ...contract, ...overridesForOperation,
    tags: [name],
    security: [{ [access === "SESSION" ? "operatorSession" : "operatorApiKey"]: [] }],
    "x-credential-admission": access,
    "x-permission-codes": permissionCodes,
    "x-default-roles": roles,
    responses,
  };
  (paths[path] ??= {})[method.toLowerCase()] = spec;
  rows.push({ signature, name, access, roles, permissionCodes, summary: spec.summary, operationId: spec.operationId });
}

function includeReferencedSchemas(value) {
  if (value === null || typeof value !== "object") return;
  if (value.$ref) {
    const name = decodeURIComponent(value.$ref.replace("#/components/schemas/", ""));
    if (!Object.hasOwn(schemas, name)) {
      const definition = donor.components.schemas[name];
      if (!definition) throw new Error(`Unknown schema: ${value.$ref}`);
      schemas[name] = definition;
      includeReferencedSchemas(definition);
    }
  }
  for (const nested of Object.values(value)) includeReferencedSchemas(nested);
}
includeReferencedSchemas(paths);

// The ops-web model also serves business onboarding, where the total is omitted.
schemas.Industry = {
  ...schemas.Industry,
  properties: { ...schemas.Industry.properties, merchantCount: integer },
  required: [...(schemas.Industry.required ?? []), "merchantCount"],
};
schemas.StoreFulfillmentRow.properties.channels = array(object({
  channel: fulfillmentChannels, enabled: boolean, denied: boolean, templateNo: nullable(string),
  pickups: array(object({ pickupNo: string, name: string, address: string, type: string, status: string })),
  locked: boolean, scopeMode: enumString("ALL", "SUBSET"), areaNos: array(string), inherited: boolean,
}, ["channel", "enabled", "denied", "locked", "scopeMode", "inherited"]));

const spec = {
  openapi: "3.1.0",
  info: {
    title: "ai-shop 运营管理 API",
    version: "1.0.0",
    description: "由 scripts/gen-ops-key-api.mjs 生成。按后端准入清单选择端点。HTTP 200 仍须检查业务 code，只有 0 表示成功。",
  },
  servers: [{ url: "http://localhost:8080", description: "本地开发。运营环境使用受控 HTTPS 入口" }],
  tags: modules.map((name) => ({ name })),
  components: {
    securitySchemes: {
      operatorApiKey: { type: "http", scheme: "bearer", description: "员工绑定的 oak_ 密钥" },
      operatorSession: { type: "http", scheme: "bearer", description: "运营交互会话 otk_" },
    },
    schemas,
  },
  paths,
};

const lines = [
  "# 运营管理 API · 模块与角色清单", "",
  "> 生成物。维护路由准入、Controller 或角色基线后运行 `node scripts/gen-ops-key-api.mjs`。", "",
  "角色来自内置授权基线。实际以员工当前角色、已配置功能点和数据域为准。任一员工可同时拥有多个角色。", "",
  "使用 [OpenAPI](./ops-management.openapi.json) 导入工具，参考[密钥分发与调用](./运营管理API-调用指南.md)。",
];
for (const name of modules) {
  lines.push("", `## ${name}`, "",
    "| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |", "|---|---|---|---|---|");
  for (const row of rows.filter((entry) => entry.name === name)) {
    lines.push(`| ${row.summary} | \`${row.signature}\` | ${admissionLabels[row.access]} | ${row.permissionCodes.join("、") || "已认证"} | ${row.roles.length === Object.keys(matrix).length ? "全部运营角色" : row.roles.join("、")} |`);
  }
}
lines.push("", "## 按内置角色检索", "",
  "| 角色 | 可用操作 ID |", "|---|---|");
for (const role of Object.keys(matrix)) {
  const operations = rows.filter((row) => row.roles.includes(role)).map((row) => `\`${row.operationId}\``);
  lines.push(`| \`${role}\` | ${operations.join("、")} |`);
}
lines.push("");
const generated = [
  ["docs/api/ops-management.openapi.json", JSON.stringify(spec, null, 2) + "\n"],
  ["docs/api/运营管理API-模块与角色清单.md", lines.join("\n")],
];
for (const [file, output] of generated) {
  if (process.argv.includes("--check")) {
    if (read(file) !== output) throw new Error(`Regenerate ${file}`);
  } else {
    writeFileSync(root + file, output);
  }
}
console.log(`运营管理 API：${rows.length} 个操作，${modules.length} 个模块`);
