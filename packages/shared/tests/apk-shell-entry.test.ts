// 安卓壳 release 的入口地址，必须是真机连得上的。
//
// 这个壳**不打包页面**：`app/build.gradle` 的 `resValue "string", "shell_entry"`
// 决定 WebView 加载谁，release 走 `http://106.55.27.246/b/`（nginx 上的 b-app），
// debug 走 `http://localhost:5174`（宿主机 dev server + adb reverse）。
//
// 于是「换个地址试试」是一次一行的改动，而改错的代价全部落在真机上：
// 把 release 指成 localhost / 10.0.2.2（模拟器对宿主机的别名）之类，
//   · 模拟器上一切正常 —— 那些地址对模拟器就是有效的
//   · 真机上整个 WebView 加载失败或所有请求连不上，
//     端上把「连不上」渲染成「这页不归你管」「未入驻」，
//     看起来像权限或入驻数据出了问题，没人会去怀疑入口地址
//   · 类型检查、单测、Gradle 构建全都不会说一句话
//
// debug 指向 localhost 是**对的**，所以这条只管 release。
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const GRADLE = join(import.meta.dirname, "../../../android-shell/app/build.gradle");
const NET_CFG = join(
  import.meta.dirname,
  "../../../android-shell/app/src/main/res/xml/network_security_config.xml",
);

/** 本机 / 模拟器专用地址：真机上这些指向的不是开发机 */
const LOCAL_ONLY = /^https?:\/\/(127\.0\.0\.1|localhost|10\.0\.2\.2|0\.0\.0\.0)(:\d+)?/;

/**
 * 取某个 buildType 块里的 shell_entry。
 *
 * **必须先定位 `buildTypes {`**：文件里 `signingConfigs { release { … } }` 排在前面，
 * 直接搜 `release {` 会先命中签名配置那个块 —— 那里没有 shell_entry，
 * 于是解析结果是 undefined，而断言会以「解析不出来」的面目失败，
 * 看起来像 gradle 写法变了，实际只是抓错了块。
 */
function entryOf(src: string, buildType: string): string | undefined {
  const at = src.indexOf("buildTypes");
  if (at < 0) return undefined;
  const scope = src.slice(at);
  const block = new RegExp(`\\b${buildType}\\s*\\{[\\s\\S]*?\\n\\s{8}\\}`).exec(scope)?.[0];
  return /resValue\s+"string",\s*"shell_entry",\s*"([^"]+)"/.exec(block ?? "")?.[1];
}

describe("安卓壳的 WebView 入口", () => {
  const has = existsSync(GRADLE);

  it.runIf(has)("★★★ release 不指向本机/模拟器地址 —— 真机上连不上", () => {
    const entry = entryOf(readFileSync(GRADLE, "utf8"), "release");
    // 取不到就是解析器跟 gradle 写法分叉了，这比断言失败更该管
    expect(entry, "没能从 app/build.gradle 的 release 块里解析出 shell_entry").toBeTruthy();
    expect(
      LOCAL_ONLY.test(entry!),
      `release 的 shell_entry 是 ${entry} —— 这是本机/模拟器地址。\n` +
        "装到真机上 WebView 会加载失败或所有请求连不上，\n" +
        "而页面会渲染成「这页不归你管」/「未入驻」，看起来像权限问题。",
    ).toBe(false);
  });

  // 这条是真机上撞出来的：release 包装上去只显示 `net::ERR_CLEARTEXT_NOT_PERMITTED`。
  // targetSdk 34 默认禁止明文 HTTP，而白名单里当时只有 10.0.2.2 / localhost，
  // 于是 debug 一切正常、release 连首页都加载不出来 —— 且 Gradle、签名、安装全部成功，
  // 没有任何一步说过一句话。
  it.runIf(has)("★★★ http 入口的 host 必须在明文白名单里 —— 否则 WebView 直接错误页", () => {
    const entry = entryOf(readFileSync(GRADLE, "utf8"), "release");
    if (!entry?.startsWith("http://")) return; // https 不需要白名单
    const host = new URL(entry).hostname;
    const cfg = readFileSync(NET_CFG, "utf8");
    const allowed = [...cfg.matchAll(/<domain[^>]*>([^<]+)<\/domain>/g)].map((m) => m[1].trim());
    expect(
      allowed,
      `release 入口是 http://${host}/…，但 network_security_config.xml 的白名单里没有它。\n` +
        `当前白名单：${allowed.join(", ") || "（空）"}\n` +
        "装上去会显示 net::ERR_CLEARTEXT_NOT_PERMITTED，而构建与安装都会成功。",
    ).toContain(host);
  });

  it.runIf(has)("release 入口与 nginx 上 b-app 的挂载路径一致", () => {
    const entry = entryOf(readFileSync(GRADLE, "utf8"), "release");
    // 少了尾斜杠，`/b` 会先吃一次 301，且相对资源会解析到站点根 —— 白屏
    expect(entry, `release 入口应以 /b/ 结尾（nginx 的 location ^~ /b/），实际 ${entry}`).toMatch(
      /\/b\/$/,
    );
  });
});
