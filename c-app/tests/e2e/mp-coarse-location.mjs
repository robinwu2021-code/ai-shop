/**
 * 小程序运行时：**位置不明时看到什么**（TDD-C端位置选择-地址取代自提点 §M5）。
 *
 * 为什么非得在小程序里跑一遍，而不是 H5 加 vitest 就够：
 *   · `getFuzzyLocation` 只有小程序有，H5 那一支永远走不到；
 *   · 「选择社区自提点」那一页删掉之后，首页那条「没绑社区怎么办」的分支
 *     在小程序上是**全新的路**，而端上判据只判源码，判不出运行时；
 *   · 请求真发得出去才算数 —— 下面第一步就是证明它发得出去，
 *     否则「首页有货」与「首页空着」都说明不了任何事。
 *
 * 用法：
 *   npm run build:mp-weixin           # 指向生产（.env.production）
 *   node tests/e2e/mp-coarse-location.mjs
 *
 * 四处 automator 不兼容见 memory 的 mp-devtools-automation。这里再记两条，
 * 都是第一版写错之后量出来的：
 *
 * 5. **`page.data()` 的键是编译后的名字**（`eO` / `uR` / `a` / `b` …）——
 *    uni 把 `<script setup>` 的 ref 名全改了。所以 `data.goods` 恒为
 *    `undefined`，而写成 `data.goods?.length === 0` 的断言**永远绿**。
 *    第一版就是这么绿的。改成**按形状找**：扫一遍 data，取第一个
 *    「元素带 goodsNo 的数组」。名字会变，形状不会。
 * 6. **归属是持久化的**（`shcr_community`），而 pinia 只在**启动时**水合。
 *    上一次跑留下的绑定会让首页直接走「有聚落」那一支 —— 于是这条 e2e
 *    一个字都没测到 M5，还报了两条看起来像真缺陷的红。
 * 7. **`cli auto` 不会重启小程序。** 项目一直开着，它只是把自动化端口接上。
 *    于是「清 storage 再跑一次 auto」等于什么都没做：内存里的 store 还在，
 *    persist 插件下一次变更又把它原样写回磁盘。实测症状极具迷惑性 ——
 *    清完读 storage 是 `[]`，跑完再读又回来了，而首页全程显示的是
 *    几周前那次会话绑的那个社区。要冷启动得 `close` → `cache --clean all` → `open`。
 */
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";

createRequire(import.meta.url)("miniprogram-automator/out/MiniProgram")
  .default.prototype.checkVersion = async () => {};

const OUT = resolve(process.cwd(), "tests/e2e/out");
const PORT = Number(process.env.MP_AUTO_PORT || 9420);
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PROJECT = resolve(process.cwd(), "dist/build/mp-weixin");

/** 线上唯一开通的地方：深圳福田 · 福民社区。**拿真实运营区测，不拿演示坐标** */
const FUTIAN = { latitude: 22.523214, longitude: 114.055984 };
/** 杭州西湖区 —— 线上一个社区都没有的地方。用来证明「筛」真的在筛 */
const XIHU = { latitude: 30.28, longitude: 120.1 };

let bad = 0;
const ok = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => { bad++; console.error(`  ✗ ${m}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function enableAutomation() {
  return new Promise((res) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)],
      { stdio: ["ignore", "pipe", "pipe"] });
    let done = false;
    const fin = () => { if (!done) { done = true; res(); } };
    p.stdout.on("data", () => setTimeout(fin, 1500));
    setTimeout(fin, 12000);
  });
}

mkdirSync(OUT, { recursive: true });

/*
 * **冷启动，不是「清一下 storage」。** 见文件头第 7 条。
 *
 * `cli cache --clean all` 清的是工具存在磁盘上的那份（storage / session / auth），
 * 必须在项目**关着**的时候清 —— 开着清，内存里的 pinia 一有变更就原样写回去。
 * 清完重新 open，小程序才真的从空状态水合。
 */
function cli(args) {
  return new Promise((res) => {
    const p = spawn(CLI, args, { stdio: ["ignore", "pipe", "pipe"] });
    p.on("close", res);
    setTimeout(res, 20000);
  });
}
await cli(["close", "--project", PROJECT]);
await sleep(2000);
await cli(["cache", "--clean", "all", "--project", PROJECT]);
await sleep(2000);
await cli(["open", "--project", PROJECT]);
await sleep(8000);
await enableAutomation();
const mp = await automator.connect({ wsEndpoint: `ws://localhost:${PORT}` });

/*
 * **自证冷启动成功。** 少了这一句，下面每一条断言都可能是在测上一次的残留状态
 * —— 第一版就是这么绿了一条、红了一条，而两条量的都不是 M5。
 */
const leftover = await mp.evaluate(() => JSON.stringify(wx.getStorageInfoSync().keys));
console.log("冷启动后的 storage →", leftover);
if (leftover.includes("shcr_community")) {
  console.error("✗ 归属还在，没冷起来 —— 后面测到的是上一次的状态，不是 M5");
  process.exit(1);
}

/*
 * **对照量先验非零。** 请求发不出去的话，下面每一条断言都会以「看起来合理」
 * 的方式变绿或变红，而它们量的其实是「这台机器连不上生产」。
 */
const probe = await mp.evaluate(() => new Promise((res) => {
  wx.request({
    url: "https://www.hxmall.top/mp/location/resolve?latE6=22523214&lngE6=114055984&coarse=true",
    success: (r) => res(JSON.stringify(r.data)),
    fail: (e) => res("fail: " + (e && e.errMsg)),
  });
  setTimeout(() => res("超时"), 8000);
}));
console.log("请求探针 →", String(probe).slice(0, 160));
if (!String(probe).includes('"code":0')) {
  console.error("✗ 小程序里请求发不出去 —— 后面的断言一条都说明不了问题");
  process.exit(1);
}
if (!String(probe).includes('"regionCode":"440304"')) {
  fail("模糊定位没给出区县 —— M5 的后端那一半没上线，或者包指错了地址");
} else {
  ok("模糊定位拿到了区县（440304 福田区）");
}

/**
 * 从页面数据里把商品列表捞出来。**按形状找，不按名字找** —— 见文件头第 5 条。
 *
 * @return 商品数组；一个都没有时返回 `[]`；**连形状都找不到时返回 null**
 *         （那说明页面还没渲染完，与「确定为空」是两件事）
 */
function goodsOf(data) {
  let empty = false;
  for (const v of Object.values(data || {})) {
    if (!Array.isArray(v)) continue;
    if (v.length && v[0] && typeof v[0] === "object" && "goodsNo" in v[0]) return v;
    if (v.length === 0) empty = true;
  }
  return empty ? [] : null;
}

async function goHome(where, label) {
  await mp.mockWxMethod("getLocation", { ...where, errMsg: "getLocation:ok" });
  // 模糊定位走的是另一个 API：两个都盖上，否则真机上那一支测不到
  await mp.mockWxMethod("getFuzzyLocation", { ...where, errMsg: "getFuzzyLocation:ok" });
  try { await mp.reLaunch("/pages/home/index"); } catch { /* 返回值不能用 */ }
  await sleep(6000);
  await mp.screenshot({ path: resolve(OUT, `mp-m5-${label}.png`) });
  return mp.currentPage();
}

// ① 真实运营区：没有任何归属，靠定位也要看得到这个区的货
let page = await goHome(FUTIAN, "futian");
const futian = goodsOf(await page.data());
if (futian === null) {
  fail("福田区：页面里找不到商品列表这个形状 —— 没渲染完，或者结构变了");
} else if (futian.length > 0) {
  ok(`福田区看得到货（${futian.length} 件）`);
} else {
  fail("福田区一件货都看不到 —— 按区筛把不该筛的也筛掉了");
}

// ② 对照：线上没有社区的区，必须是空，**不是**一屏全平台的货
page = await goHome(XIHU, "xihu");
const xihu = goodsOf(await page.data());
if (xihu === null) {
  fail("西湖区：页面里找不到商品列表这个形状");
} else if (xihu.length === 0) {
  ok("西湖区是空的 —— 没有回落成全平台");
} else {
  fail(`西湖区看到了 ${xihu.length} 件货 —— 那是全平台的货，正是这一单要消灭的状态`);
}

/*
 * **两边都要验，才叫「筛」。** 只验福田有货，「不筛」也会绿；
 * 只验西湖为空，「什么都筛掉」也会绿。一多一少同时成立才排除得掉这两种。
 */
if (futian?.length > 0 && xihu?.length === 0) {
  ok("对照成立：同一份目录，福田出货、西湖出空");
}

console.log(bad ? `\n✗ ${bad} 条不对` : "\n✓ 全部通过");
try { await mp.disconnect(); } catch { /* ignore */ }
process.exit(bad ? 1 : 0);
