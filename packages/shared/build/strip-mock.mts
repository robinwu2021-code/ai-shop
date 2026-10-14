import { sep } from "node:path";

/**
 * 构建期剔除 mock（2026-10-09）。**c-app 与 b-app 共用这一份**。
 *
 * <p>`VITE_USE_MOCK=0` 时，mock 的代码与**种子数据**不该进产物 —— 而此前它们一直在：
 * 两端的 `App.vue` 都无条件 `import { restoreDb } from "@shared/mock/db"`，
 * 调用虽然有 `if (USE_MOCK)` 守着，但**静态 import 让 2659 行的种子库始终进模块图**，
 * rollup 不敢摇掉有副作用的模块。于是生产小程序里带着「本地绿叶菜组合」这类演示商品，
 * 连三语译名一起。
 *
 * <p>体积不是主要理由（c-app 实测 10KB）。理由是 **mock 正在逐步退出**，
 * 而「生产包里有演示数据」不该靠「反正开关是关的、不会被执行」来接受 ——
 * 那是一句关于当下配置的话，不是关于产物的话。
 *
 * <p>做法：把两个入口换成桩，整条 mock 链就从模块图里断了。
 * **桩只给非 mock 代码真正用到的那两个名字**（`restoreDb` / `mockApi`）——
 * 将来谁在生产路径上引了别的名字，构建期就报 `does not provide an export`，
 * 而不是运行时拿到 undefined。`mockApi` 给的是会抛的 Proxy：
 * 万一开关判断出错走到它，要当场炸，别静默返回空数据让人以为后端没数据。
 *
 * @param useMock 构建期的开关。调用方用 vite 的 `loadEnv` 取，
 *   它**同时收 shell 变量与 .env 文件**（dev server 靠 `VITE_USE_MOCK=1` 走 shell 那条）。
 * @param extra 各端自己的演示数据模块。两端共用的只有上面那两个；
 *   b-app 还有 `api/demo-orders`（演示店与演示订单），那是它一家的事，
 *   所以由它在自己的 vite 配置里登记，而不是把 b-app 的知识塞进这份共享件。
 */
export function stripMock(useMock: boolean, extra: { test: RegExp; code: string }[] = []) {
  const DB = "\0strip-mock:db";
  const API = "\0strip-mock:api";
  return {
    name: "ai-shop:strip-mock",
    enforce: "pre" as const,
    resolveId(id: string, importer?: string) {
      if (useMock) return null;
      /*
       * 裸 id 与 alias 解析后的绝对路径**都要认**：别名插件同在 pre 阶段，
       * 先后顺序不保证。只认裸 id 的第一版就漏了 —— `mockApi` 剔掉了，
       * 而种子库照旧在包里，表现成「改了一半」。
       */
      if (id === "@shared/mock/db" || /[\\/]shared[\\/]src[\\/]mock[\\/]db(\.ts)?$/.test(id)) {
        return DB;
      }
      // 两端的 `api/index.ts` 里写的都是相对路径 "./mock"
      if (id === "./mock" && importer && importer.includes(`${sep}api${sep}`)) {
        return API;
      }
      for (let i = 0; i < extra.length; i++) {
        if (extra[i]!.test.test(id)) return `\0strip-mock:extra${i}`;
      }
      return null;
    },
    load(id: string) {
      if (id === DB) {
        return "export const restoreDb = () => {};\n";
      }
      if (id === API) {
        return (
          "export const mockApi = new Proxy({}, { get(_t, k) {\n" +
          '  throw new Error("mock 已被构建期剔除（VITE_USE_MOCK=0），不该调到 " + String(k));\n' +
          "} });\n"
        );
      }
      const m = /^\0strip-mock:extra(\d+)$/.exec(id);
      if (m) return extra[Number(m[1])]!.code;
      if (useMock) return null;
      /*
       * **`load` 上再拦一道**：别名插件也在 pre 阶段，而且排在最前 ——
       * 它把 `@/api/demo-orders` 解析成绝对路径后就结束了 resolveId 链，
       * 我们那一钩根本收不到（2026-10-09 实测：加日志一条都没打出来）。
       * 而 `load` 是按**解析后的真实路径**调的，每个模块都会过这里。
       */
      const real = id.split("?")[0]!;
      if (/[\\/]shared[\\/]src[\\/]mock[\\/]db\.ts$/.test(real)) {
        return "export const restoreDb = () => {};\n";
      }
      for (const e of extra) {
        if (e.test.test(real)) return e.code;
      }
      return null;
    },
  };
}
