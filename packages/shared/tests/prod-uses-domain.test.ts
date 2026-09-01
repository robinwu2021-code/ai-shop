// 生产环境的地址必须是备案域名，不能是 IP。
//
// 2026-09-01 备案下来，三端统一走 https://www.hxmall.top，IP 不再支持。
// 而 `.env.production` 里两端都还写着 `http://106.55.27.246` ——
// **打出来的 App 会连一个不再服务的地址**，端上看到的是「连不上」，
// 而端上把「连不上」渲染成了「这页不归你管」和「未入驻」（那段历史就写在
// b-app/.env.production 的注释里），看起来像权限问题。
//
// 这条闸门盯的就是那个文件：改域名是件一年一次的事，而漏掉它的代价
// 要等到装机之后才看得见。
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/** 生产打包会读的 env 文件 */
const PROD_ENVS = ["b-app/.env.production", "c-app/.env.production"];

/** 形如 http(s)://1.2.3.4 —— 端口可有可无 */
const IP_URL = /https?:\/\/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?/g;

/** 本机地址不算「生产写了 IP」—— 它们只在开发时有意义，也不会被生产打包读到 */
const LOCAL = /^https?:\/\/(127\.0\.0\.1|0\.0\.0\.0)/;

describe("生产地址", () => {
  it("★★★ .env.production 里不许出现 IP —— 打出来的 App 会连一个不再服务的地址", () => {
    const offenders: string[] = [];
    let scanned = 0;

    for (const rel of PROD_ENVS) {
      const p = join(ROOT, rel);
      /*
       * **文件必须存在。** 少扫一个文件在这条闸门上表现为「没有违规」，
       * 与全绿一模一样 —— 而文件被删或改名恰恰是它会失效的方式。
       */
      expect(existsSync(p), `${rel} 不在了 —— 改名了？那这条闸门对它从此恒真`).toBe(true);
      scanned++;

      const text = readFileSync(p, "utf8");
      for (const line of text.split("\n")) {
        if (line.trim().startsWith("#")) continue;   // 注释里提 IP 无所谓
        for (const hit of line.match(IP_URL) ?? []) {
          if (LOCAL.test(hit)) continue;
          offenders.push(`${rel}: ${hit}`);
        }
      }
    }

    expect(scanned, "一个文件都没扫到").toBeGreaterThan(0);
    expect(
      offenders,
      `生产 env 里写着 IP：\n  ${offenders.join("\n  ")}\n` +
        "  2026-09-01 起三端统一走 https://www.hxmall.top，IP 不再服务。\n" +
        "  打出来的包会连不上，而端上把「连不上」渲染成「这页不归你管」/「未入驻」——\n" +
        "  看起来像权限问题，实际是地址错了。",
    ).toEqual([]);
  });

  it("★★ 生产 env 必须真的配了 API 地址 —— 留空在 App 里不是「同源」，是连不上", () => {
    for (const rel of PROD_ENVS) {
      const text = readFileSync(join(ROOT, rel), "utf8");
      const line = text.split("\n").find((l) => l.startsWith("VITE_API_BASE="));
      expect(line, `${rel} 里没有 VITE_API_BASE`).toBeDefined();
      const value = line!.slice("VITE_API_BASE=".length).trim();
      /*
       * H5 留空可以走同源相对路径，**而 App 里没有「同源」这回事** ——
       * 装到手机上的包没有页面来源，留空的结果是请求发到 file:// 上。
       * 这个文件是打 App 用的，所以这里必须是完整地址。
       */
      expect(value, `${rel} 的 VITE_API_BASE 是空的`).not.toBe("");
      expect(value, `${rel} 的 VITE_API_BASE 应是 https 完整域名，现在是 ${value}`)
        .toMatch(/^https:\/\//);
    }
  });
});
