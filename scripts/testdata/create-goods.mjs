#!/usr/bin/env node
/**
 * 按 `goods.tsv` 建测试商品 —— **走真实接口，不写库**。
 *
 * 为什么不做成种子：种子是 `INSERT`，它绕过校验、绕过权限、绕过审核状态机、绕过图片上传。
 * 灌完看着有 180 条数据，而这条链路上**没有一行业务代码被执行过** ——
 * 真出问题的地方（必填校验、类目准入、SKU 矩阵、封面 URL）一处也测不到。
 * 实测代价：第一版做成种子灌进生产，启动直接崩在一个唯一键上，
 * 而那个唯一键正是走接口时后端会替你处理好的东西。
 *
 * 这个脚本与 B 端界面调的是同一批端点：
 *   POST /biz/auth/otp/send → /biz/auth/login   （拿会话）
 *   POST /biz/upload/image                       （封面，真上 COS）
 *   POST /biz/goods/save                         （建品，含 SKU 与规格）
 *   POST /biz/goods/{no}/toggle                  （上下架，覆盖四态）
 *
 * 用法：
 *   node scripts/testdata/create-goods.mjs --base http://127.0.0.1:8081 --phone 18503088359 --otp 123456
 *   加 --only CVS 只建一类；加 --limit 5 每类只建 5 条（先小跑一遍再放量）
 *   加 --dry 只打印不发请求
 */
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

// ---- 参数 ----
const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv[i + 1] : def;
};
const has = (name) => argv.includes(`--${name}`);

const BASE = arg("base", "http://127.0.0.1:8081");
const PHONE = arg("phone", "");
const OTP = arg("otp", "");
const ONLY = arg("only", "");
const LIMIT = Number(arg("limit", "0")) || 0;
const DRY = has("dry");

if (!DRY && (!PHONE || !OTP)) {
  console.error("缺 --phone / --otp。**不要把验证码写进脚本或提交进库**，运行时传。");
  process.exit(2);
}

// ---- 封面图：按品名生成，一类一套配色 ----
// 不引图形库：PNG 可以手写，而为了占位图拉一个依赖不值当。
const PALETTE = {
  CVS: [0xe1, 0x25, 0x1b],
  FRESH: [0x2e, 0x9e, 0x4f],
  DELI: [0xc9, 0x6a, 0x1b],
  PHAR: [0x1b, 0x74, 0xc9],
  SERV: [0x6b, 0x4b, 0xd6],
  CARD: [0x17, 0x18, 0x1a],
};

/** 极小 PNG 编码器：纯色底 + 一条对角纹，够用来区分「哪张图落到了哪个商品」 */
function png(w, h, rgb, seed) {
  const zlib = require("node:zlib");
  const raw = Buffer.alloc((w * 3 + 1) * h);
  let p = 0;
  for (let y = 0; y < h; y++) {
    raw[p++] = 0; // filter: none
    for (let x = 0; x < w; x++) {
      // 对角纹按 seed 偏移 —— 同类商品的图不至于长得一模一样
      const on = ((x + y + seed * 7) % 64) < 8;
      const [r, g, b] = rgb;
      raw[p++] = on ? 255 : r;
      raw[p++] = on ? 255 : g;
      raw[p++] = on ? 255 : b;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2; // truecolor
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = -1;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}

// Node ESM 里没有 require，补一个
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);

// ---- HTTP ----
let TOKEN = "";
async function call(path, body, method = "POST") {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`${path} → HTTP ${res.status}，响应不是 JSON：${text.slice(0, 120)}`);
  }
  if (json.code !== 0) throw new Error(`${path} → [${json.code}] ${json.msg}`);
  return json.data;
}

async function uploadCover(bytes, name) {
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: "image/png" }), name);
  const res = await fetch(BASE + "/biz/upload/image", {
    method: "POST",
    headers: { Authorization: `Bearer ${TOKEN}` },
    body: form,
  });
  const json = await res.json();
  if (json.code !== 0) throw new Error(`上传封面失败：[${json.code}] ${json.msg}`);
  return json.data.url;
}

// ---- 主流程 ----
const rows = readFileSync(join(HERE, "goods.tsv"), "utf8")
  .split("\n")
  .slice(1)
  .filter((l) => l.trim())
  .map((l) => l.split("\t"))
  .filter((c) => !ONLY || c[0] === ONLY);

const byBiz = new Map();
for (const c of rows) {
  if (!byBiz.has(c[0])) byBiz.set(c[0], []);
  const list = byBiz.get(c[0]);
  if (!LIMIT || list.length < LIMIT) list.push(c);
}

const plan = [...byBiz.values()].flat();
console.log(`计划建 ${plan.length} 条（${[...byBiz.keys()].join("/")}）${DRY ? " —— dry run" : ""}`);

if (!DRY) {
  /*
   * 发码有 60 秒频控，而这个脚本多半是接着别的调用跑的 —— 撞上是常态不是意外。
   * 报错里带着还要等几秒，照它等就行；一上来就失败会让人以为是账号或网络的问题。
   */
  for (let i = 0; i < 12; i++) {
    try {
      await call("/biz/auth/otp/send", { phone: PHONE });
      break;
    } catch (e) {
      const wait = Number(/请 (\d+) 秒/.exec(e.message)?.[1]);
      if (!wait) throw e;
      console.log(`  发码频控，等 ${wait + 2}s`);
      await new Promise((r) => setTimeout(r, (wait + 2) * 1000));
    }
  }
  const login = await call("/biz/auth/login", {
    grantType: "PHONE_OTP",
    principal: PHONE,
    credential: OTP,
    agreed: true,
  });
  TOKEN = login.token;
  console.log(`已登录：${login.name ?? login.merchantNo ?? ""}`);
}

let ok = 0;
const failures = [];
for (const [i, c] of plan.entries()) {
  const [biz, , goodsNo, type, categoryNo, , title, subtitle, auditStatus, onSale, , skuField] = c;
  try {
    if (DRY) {
      console.log(`  [dry] ${biz} ${title}`);
      continue;
    }
    const cover = await uploadCover(png(360, 360, PALETTE[biz] ?? [128, 128, 128], i), `${goodsNo}.png`);
    /*
     * **包体形状以 BizGoodsController.SaveGoodsReq 为准**，不是端上的 contract.ts：
     *   · title/subtitle 是**普通字符串**，三语走另外两个 i18n Map
     *   · specGroups.options 是字符串数组，不是 i18n 对象
     *   · SkuReq 里**没有 originPrice** —— 划线价不由这个端点写
     * 照 contract.ts 猜的第一版全军覆没，12 条一律 10400，而 10400 不会告诉你是哪个字段。
     */
    const skus = skuField.split(";").map((part) => {
      const [spec, price, , stock] = part.split(":");
      return {
        optionValues: [spec],
        price: Number(price),
        // 键是**市场码**不是币种码（见 contract.ts 的说明）
        priceByMarket: { CN: Number(price) },
        stock: Number(stock),
      };
    });
    const saved = await call("/biz/goods/save", {
      title,
      subtitle,
      titleI18n: { "zh-CN": title },
      subtitleI18n: { "zh-CN": subtitle },
      type,
      categoryNo,
      cover,
      images: [],
      specGroups: [{ name: "规格", options: skus.map((s) => s.optionValues[0]) }],
      skus,
      /*
       * 履约方式跟着品类走。取值只能来自 FULFILLMENT 那七个 ——
       * 第一版给卡券编了个 "VIRTUAL"，而枚举里根本没有它，30 条卡券全数 10400。
       * 卡券走到店核销（STORE_VERIFY），服务走预约，其余到店自提。
       */
      fulfillments:
        type === "SERVICE" ? ["APPOINTMENT"] : type === "CARD" || type === "VIRTUAL" ? ["STORE_VERIFY"] : ["STORE_PICKUP"],
    });
    /*
     * 建出来一律是**审核中**（后端 applyStatus 强制），所以「在售/已下架」这两态
     * 只能靠运营审过之后再 toggle。这里只把该下架的下架 ——
     * 审过与驳回要走运营端，脚本不该替它决定。
     */
    if (auditStatus === "APPROVED" && onSale === "0") {
      await call(`/biz/goods/${saved.goodsNo}/toggle`, { onSale: false });
    }
    ok++;
    if (ok % 10 === 0) console.log(`  已建 ${ok}/${plan.length}`);
  } catch (e) {
    failures.push(`${biz} ${title}：${e.message}`);
  }
}

console.log(`\n完成：成功 ${ok}，失败 ${failures.length}`);
if (failures.length) {
  console.log("失败明细（前 20 条）：");
  failures.slice(0, 20).forEach((f) => console.log("  " + f));
  process.exitCode = 1;
}
