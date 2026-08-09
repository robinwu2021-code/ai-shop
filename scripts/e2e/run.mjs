// E2E-2 runner：顺序跑旅程，打真服务端。
//
// 用法：
//   npm run e2e            —— 需要后端已在 8080 跑着
//   E2E_BASE=http://host:port npm run e2e
//
// 与后端那套 E2E-1（`mvn test -Pe2e`）的分工：
//   E2E-1 起自己的容器与库，验后端自洽；
//   E2E-2 **打一个真实跑着的服务端**，验端上的契约能不能真的跑通 ——
//   它连库都不碰，因为端也不碰库。
import { ping, shapeIssues, E2eError } from "./client.mjs";

const JOURNEYS = [await import("./journeys/j1-merchant-go-live.mjs")];

const t0 = Date.now();

if (!(await ping())) {
  // 先探一下再跑：否则第一条旅程报的是一个看不懂的 fetch 连接错误
  console.error("✗ 连不上后端（E2E_BASE=" + (process.env.E2E_BASE ?? "http://localhost:8080") + "）");
  console.error("  先起后端：cd backend && mvn -f shop-app/pom.xml spring-boot:run \\");
  console.error("            -Dspring-boot.run.arguments='--shop.seed.enabled=true --shop.pay.stub=true'");
  process.exit(1);
}

let failed = 0;
for (const journey of JOURNEYS) {
  const start = Date.now();
  console.log(`\n▶ ${journey.name}`);
  let n = 0;
  const step = (what, detail) => {
    n += 1;
    console.log(`  [${String(n).padStart(2, "0")}] ${what.padEnd(24)} ${detail ?? ""}`);
  };
  try {
    await journey.run(step);
    console.log(`  ✓ 通过（${Date.now() - start}ms）`);
  } catch (e) {
    failed += 1;
    console.error(`  ✗ 断在第 ${n + 1} 步：${e.message}`);
    // 失败时打响应体全文 —— 字段错配这类问题，看一眼响应就够了
    if (e instanceof E2eError && e.context) {
      console.error("    上下文：" + JSON.stringify(e.context).slice(0, 600));
    }
  }
}

/*
 * 形状问题单独report：它不会让某一步失败（字段缺了接口照样 200），
 * 但**端上拿到的是 undefined** —— 页面渲染出空白且不报错。
 * 这正是 E2E-2 存在的理由，所以它必须让整个 run 变红。
 */
if (shapeIssues.length) {
  console.error(`\n✗ 响应形状与端上声明不符（${shapeIssues.length} 处）：`);
  for (const issue of [...new Set(shapeIssues)]) console.error("    " + issue);
  console.error("  端上按自己的类型解析，缺字段拿到的是 undefined —— 页面空白且不报错");
  failed += 1;
}

console.log(`\n${failed ? "✗" : "✓"} E2E-2 结束，用时 ${Date.now() - t0}ms`);
process.exit(failed ? 1 : 0);
