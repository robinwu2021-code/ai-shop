// 小程序代码上传（体验版）。**不提审、不发布** —— 那两步是不可逆的对外动作，
// 留在 mp 后台由人点，脚本只做「把这份产物传上去」。
//
// 前置（都要你在 mp 后台拿，脚本代替不了）：
//   1. 开发管理 → 开发设置 → 小程序代码上传 → 生成密钥，下载 private.<appid>.key
//      放到 c-app/ 下（.gitignore 已忽略 *.key）
//   2. 同一页配 IP 白名单，把这台机器的公网 IP 加进去，否则上传报 -10007
//   3. npm i -D miniprogram-ci -w ai-shop-c-app
//
// 用法：
//   node scripts/upload-mp.mjs 0.1.0 "微信登录联调"
//
// 上传完**体验版就跟到这一版**（2026-09-06 确认，后台不用再点），拿体验版码的人
// 直接就能看到新的。提审与发布另说 —— 那两步没有接口，只能人去后台。
import { readFileSync } from "node:fs";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import ci from "miniprogram-ci";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");

const [version = "0.0.1", desc = "自动上传"] = process.argv.slice(2);

const manifest = JSON.parse(readFileSync(resolve(root, "src/manifest.json"), "utf8"));
const appid = manifest["mp-weixin"]?.appid;
if (!appid) throw new Error("manifest.json 的 mp-weixin.appid 为空");

// 上传的是**构建产物**，不是源码。忘了 build 会把上一次的包再传一遍，
// 而版本号是新的 —— 那种「传上去了但改动没生效」最难查。
const projectPath = resolve(root, "dist/build/mp-weixin");
if (!existsSync(resolve(projectPath, "app.json"))) {
  throw new Error(`产物不存在：${projectPath}\n先跑 npm run build:mp-weixin`);
}

const privateKeyPath = process.env.WX_UPLOAD_KEY || resolve(root, `private.${appid}.key`);
if (!existsSync(privateKeyPath)) {
  throw new Error(
    `找不到上传密钥：${privateKeyPath}\n` +
      "mp 后台 → 开发管理 → 开发设置 → 小程序代码上传 → 生成并下载",
  );
}

const project = new ci.Project({ appid, type: "miniProgram", projectPath, privateKeyPath });

console.log(`[upload] appid=${appid} version=${version} desc=${desc}`);

/*
 * 包住 ci.upload：它失败时抛的 CodeError 里带着**整个压缩过的 upload.js 源码**，
 * 一屏几万字符，真正有用的就 errCode 那一行。这里把常见的几种翻译成人话。
 */
try {
  const result = await ci.upload({
    project,
    version,
    desc,
    setting: { es6: false, minify: true },
    onProgressUpdate: (t) => process.stdout.write(typeof t === "string" ? `${t}\n` : ""),
  });
  console.log("[upload] 完成", JSON.stringify(result?.subPackageInfo ?? {}, null, 2));
  console.log("[upload] 体验版已跟到这一版；要上线的话去后台提审（没有接口）");
} catch (e) {
  const raw = String(e?.message ?? e);
  const ip = raw.match(/invalid ip: ([\d.]+)/)?.[1];
  const hint = ip
    ? `公网 IP ${ip} 不在白名单里。后台 → 开发管理 → 开发设置 → 小程序代码上传 → IP 白名单，加上它。`
    : /-10007/.test(raw)
      ? "上传密钥无效或已被重新生成。重新下载一份覆盖 " + privateKeyPath
      : raw.match(/"errMsg":"([^"]+)"/)?.[1] || raw.slice(0, 300);
  console.error(`\n[upload] 失败：${hint}\n`);
  process.exit(1);
}
