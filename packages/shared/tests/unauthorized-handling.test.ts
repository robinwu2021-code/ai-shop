// 登录失效（401）必须**由 App 壳统一处理**，且只有一处。
//
// 这条守卫来自两次同形的故障，一端一次：
//
//   · C 端一处也没接：令牌过期后整页渲染成空白 + 一个未捕获错误 ——
//     没有提示、没有跳转，刷新也一样（token 还在存储里躺着）。
//     用户唯一能做的是清缓存，而他不知道要清缓存。
//   · B 端接了，但接在 `loadScope` 的 catch 里，只有 `/biz/scope` 那一个请求算数：
//     从首页进来会跳登录，在商品页点保存收到的 401 什么也不发生。
//     同一件事两种表现，取决于他从哪一页进来。
//
// 401 可以从任何一个请求上回来，而「哪一页发的请求」与「该去哪」无关 ——
// 所以它属于传输层的回调 + 壳上的一次注册，不属于任何一页。
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

const APPS = ["c-app", "b-app"];

/** 某个 app 下所有页面与 store 的源码 */
function pagesAndStores(app: string): { name: string; src: string }[] {
  const out: { name: string; src: string }[] = [];
  const pages = join(ROOT, app, "src/pages");
  for (const d of readdirSync(pages)) {
    try {
      out.push({ name: `pages/${d}`, src: readFileSync(join(pages, d, "index.vue"), "utf8") });
    } catch {
      // 没有 index.vue 的目录（分包/资源），跳过
    }
  }
  const stores = join(ROOT, app, "src/stores");
  for (const f of readdirSync(stores)) {
    if (f.endsWith(".ts")) out.push({ name: `stores/${f}`, src: readFileSync(join(stores, f), "utf8") });
  }
  return out;
}

describe("登录失效的处理", () => {
  it("★★★ 传输层要有 401 回调 —— 没有它，各页只能各自 catch，必然漏", () => {
    const client = read("packages/shared/src/net/http-client.ts");
    expect(client).toContain("setUnauthorizedHandler");
    expect(
      /statusCode === 401[\s\S]{0,400}onUnauthorized/.test(client),
      "401 分支里必须真的调用注册的处理函数（只导出一个 setter 等于没接）",
    ).toBe(true);
  });

  it.each(APPS)("★★★ %s 必须在 App.vue 上注册一次 —— 靠某一页 catch = 从别的页进来就静默", (app) => {
    const shell = read(`${app}/src/App.vue`);
    expect(
      shell.includes("setUnauthorizedHandler("),
      `${app}/src/App.vue 没有注册 401 处理：`
        + "令牌过期后页面会渲染成空白或「这页不归你管」，而真相只是要重新登录一次。",
    ).toBe(true);
    // 跳登录本身要有：注册了却什么也不做，等于没注册
    expect(/setUnauthorizedHandler\([\s\S]{0,600}reLaunch/.test(shell)).toBe(true);
  });

  it.each(APPS)("★★ %s 的页面/store 不许自己跳登录 —— 两处各跳一次会互相打架", (app) => {
    const offenders = pagesAndStores(app)
      .filter((f) => /reLaunch\(\{\s*url:\s*["'`][^"'`]*login/.test(f.src))
      .map((f) => f.name);

    expect(
      offenders,
      "这些文件自己跳了登录页 ——\n"
        + "  401 该由 App.vue 注册的那一处统一处理。散着写的结果是：\n"
        + "  有的请求跳、有的不跳，且并发时会连跳几次把提示刷掉。\n"
        + "  修：删掉这里的跳转，只保留清本地登录态。\n  "
        + offenders.join("\n  "),
    ).toEqual([]);
  });
});
