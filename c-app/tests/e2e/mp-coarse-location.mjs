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
 * 8. **`mockWxMethod("getLocation")` 换不了这条 e2e 的位置。** 冷启动一起来，
 *    app 就用工具里那个真实定位当场绑好了聚落，而那一步在 automator 连上之前。
 *    之后 `ensureCoarseRegion` 早退，mock 的坐标一次都用不上 —— 三个位置量到同一个
 *    聚落号。详见 ②③ 上面那段。**这一层只测客户端独有的那半**，距离分档在后端测。
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
/** 龙华 —— 离福民社区约 19 公里：**围栏外、上限内**，M6 该把它绑过去 */
const LONGHUA = { latitude: 22.69, longitude: 114.03 };
/** 北京 —— 约 1940 公里，超出默认上限。到这一级才该是空的 */
const BEIJING = { latitude: 39.904, longitude: 116.407 };

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
/** 只有这条 e2e 会写的键；app 里 grep 不到它。 */
const SENTINEL = "shcr_e2e_coldstart";
let planted = false;

/**
 * 冷启动一次。**每换一个位置都要来一遍。**
 *
 * 第 10 处坑：绑过聚落之后 `ensureCoarseRegion` 会早退（那是产品该有的行为 ——
 * 一个已经有归属的人不该因为走远了就被悄悄换掉）。于是同一次会话里连着换三个位置，
 * 后两个量到的都是第一个留下的归属：北京那一格报「绑了聚落」，看起来像上限失效，
 * 其实是这条 e2e 自己没把状态清干净。
 */
async function coldStart() {
  await cli(["close", "--project", PROJECT]);
  await sleep(2000);
/*
 * **只清 storage 与 session，不清 auth。**
 * `--clean all` 把定位授权也清了，于是每次跑都弹一次授权框挂在那儿 ——
 * 而 `getLocation` 就卡在弹框上，`ensureCoarseRegion` 拿不到坐标、返回 null，
 * 首页落到「还不知道你在哪儿」。截图上那个空态看起来完全像一条真缺陷，
 * 实际是这条 e2e 自己造出来的。
 */
await cli(["cache", "--clean", "storage", "--project", PROJECT]);
await sleep(1500);
await cli(["cache", "--clean", "session", "--project", PROJECT]);
await sleep(2000);
  await cli(["open", "--project", PROJECT]);
  await sleep(8000);
  await enableAutomation();
  const m = await automator.connect({ wsEndpoint: `ws://localhost:${PORT}` });
  /*
   * **自证冷启动成功 —— 判据是哨兵，不是归属。**
   *
   * 第一版拿 `shcr_community` 当判据：storage 里还有归属就判没冷起来。
   * 深圳的小区开出来之后这条**变成了误报** —— 一个真的冷起来的会话，
   * 在龙华的坐标上本来就会在启动时当场绑上聚落，`shcr_community` 出现是对的。
   * 而且它从来量不准反向：清干净了、可 app 又写回来，与根本没清，看到的是同一件事。
   *
   * 改成量一个**只有这条 e2e 会写、app 永远不会写**的键：
   * 每次连上就种下，下一次冷启动之后它必须消失。这直接量的是「storage 清掉了没有」，
   * 与 app 绑不绑聚落无关。
   */
  const leftover = JSON.parse(await m.evaluate(() => JSON.stringify(wx.getStorageInfoSync().keys)));
  if (leftover.includes(SENTINEL)) {
    console.error(`✗ 哨兵还在，storage 没清掉 —— 后面测到的是上一次的状态（${leftover.join()}）`);
    process.exit(1);
  }
  if (!planted) {
    // 本次进程的第一次冷启动：上一轮的哨兵可能压根没种过（换了机器、清过缓存）。
    // 这一次没法证伪，如实说；真正有风险的是同一次会话里连着换位置，那些都严格量。
    console.log("· 首次冷启动：上一轮没有哨兵可比，跳过（后续每次都会严格判）");
  }
  await m.evaluate((k) => wx.setStorageSync(k, Date.now()), SENTINEL);
  planted = true;
  return m;
}

let mp = await coldStart();

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
 * 量**首页要货时带了什么条件**，而不是量页面里渲染出了什么。
 *
 * <p>第 9 处坑：`page.data()` 那条路走不通 —— 键名被编译改掉（第 5 条），
 * 按形状扫也不稳（商品列表在自定义组件里，页面级 data 上未必有）。
 * 两版断言都报了「一件货都看不到」，而截图上货**明明在那儿**。
 *
 * <p>请求参数是更直的判据，而且正好是这一单要验的东西：
 * 绑上了聚落就带 `communityNo`，只落到区就带 `regionCode`，都没有就一个都不带。
 * uni 的 GET 参数在 `o.data` 里，不在 URL 上 —— 只看 URL 会看到一条光秃秃的 /mp/goods。
 */
async function tapRequests() {
  await mp.evaluate(() => {
    if (!globalThis.__log) {
      globalThis.__log = [];
      const orig = wx.request;
      wx.request = (o) => {
        globalThis.__log.push(o.url + " :: " + JSON.stringify(o.data || {}));
        return orig(o);
      };
    }
    globalThis.__log.length = 0;
  });
}

/** 首页那次要货带的条件；没发过就是 null */
async function goodsQuery() {
  const log = JSON.parse(await mp.evaluate(() => JSON.stringify(globalThis.__log || [])));
  const hit = log.find((l) => l.includes("/mp/goods ::"));
  return hit ? JSON.parse(hit.split(" :: ")[1]) : null;
}

/**
 * 定位授权框有没有挂在那儿。**它一挂，下面每一条断言量的都是「没拿到坐标」。**
 *
 * <p>第 8 处 automator 不兼容：`mockWxMethod("getLocation", …)` **拦不住授权框** ——
 * 工具仍然先走权限检查，弹框是模态的，`getLocation` 就停在那儿不返回。
 * 于是首页落到「还不知道你在哪儿」，截图看起来完全像一条真缺陷。
 * 我为此追错了两轮（先怪按区筛、又怪 `cache --clean all` 把授权清了）。
 *
 * <p>没有接口能读到那个框，所以按**症状**判：定位一直不返回。
 * 判出来就**明说是环境没就绪**，不报成产品缺陷 —— 假红比没有断言更坏。
 */
async function locationBlocked() {
  await mp.mockWxMethod("getSetting", {
    authSetting: { "scope.userLocation": true }, errMsg: "getSetting:ok",
  });
  await mp.mockWxMethod("authorize", { errMsg: "authorize:ok" });
  await mp.mockWxMethod("getLocation", { ...FUTIAN, errMsg: "getLocation:ok" });
  const r = await mp.evaluate(() => new Promise((res) => {
    wx.getLocation({ type: "gcj02", success: () => res("ok"), fail: () => res("fail") });
    setTimeout(() => res("挂住"), 4000);
  }));
  return String(r) === "挂住";
}

async function goHome(where, label) {
  // 换位置就要冷启动一次，理由见 coldStart 的注释
  try { await mp.disconnect(); } catch { /* ignore */ }
  mp = await coldStart();
  /*
   * **先把权限检查也 mock 掉，`getLocation` 的 mock 才生效**（第 8 处不兼容）。
   * 只 mock `getLocation` 的话，工具仍然先走权限检查、弹出模态授权框，
   * 而 `getLocation` 就停在那儿不返回 —— 首页落到「还不知道你在哪儿」，
   * 截图看起来完全像一条真缺陷。我为此追错了两轮。
   */
  await mp.mockWxMethod("getSetting", {
    authSetting: { "scope.userLocation": true, "scope.userFuzzyLocation": true },
    errMsg: "getSetting:ok",
  });
  await mp.mockWxMethod("authorize", { errMsg: "authorize:ok" });
  await mp.mockWxMethod("getLocation", { ...where, errMsg: "getLocation:ok" });
  // 模糊定位走的是另一个 API：两个都盖上，否则真机上那一支测不到
  await mp.mockWxMethod("getFuzzyLocation", { ...where, errMsg: "getFuzzyLocation:ok" });
  /*
   * **冷启动之后小程序自己已经把首页加载过一遍了** —— 那一遍用的是工具里设的真实定位，
   * 而且发生在我装上请求记录之前。所以要先离开首页、装好记录、再回来，
   * 逼它重新走一次 onShow。只 reLaunch 到当前页量到的是 null：
   * 那一次请求早就发完了，而 null 看起来像「端上什么条件都没带」。
   */
  try { await mp.reLaunch("/pages/category/index"); } catch { /* 返回值不能用 */ }
  await sleep(2500);
  await tapRequests();
  try { await mp.reLaunch("/pages/home/index"); } catch { /* 返回值不能用 */ }
  await sleep(7000);
  await mp.screenshot({ path: resolve(OUT, `mp-m5-${label}.png`) });
  return mp.currentPage();
}

if (await locationBlocked()) {
  console.error("\n⚠️ 定位授权框挂在开发者工具上，`getLocation` 不返回 —— 这条 e2e 测不了。");
  console.error("   在工具里点一次「允许」，之后它会记住（本脚本刻意不清 auth）。");
  console.error("   **不把下面的断言跑完再报红**：那种红量的是环境，不是产品。");
  try { await mp.disconnect(); } catch { /* ignore */ }
  process.exit(2);
}

// ① 站在聚落里：按 communityNo 要货
await goHome(FUTIAN, "futian");
const futian = await goodsQuery();
if (futian?.communityNo) {
  ok(`福田（围栏内）按聚落要货：communityNo=${futian.communityNo}`);
} else {
  fail(`福田（围栏内）没按聚落要货，带的是 ${JSON.stringify(futian)}`);
}

/*
 * **②③ 改成在小程序里量服务端契约，不再假装客户端换了位置。**
 *
 * 原来这两格是 `goHome(LONGHUA)` / `goHome(BEIJING)`，靠 `mockWxMethod("getLocation")`
 * 换坐标。深圳的小区开出来之后这条路**断了，而且是静默断的**：冷启动一起来，
 * app 就用开发者工具里那个真实定位当场绑好了聚落 —— 这一步发生在 automator
 * 连上、mock 装好**之前**。之后 `ensureCoarseRegion` 对一个已有归属的人早退
 * （那是产品该有的行为），mock 的坐标一次都没被用到。
 *
 * 症状：三个位置量到的是**同一个聚落号**，连北京都是。于是「龙华 ✓」是假绿
 * （它量的是福田那次绑定），「北京 ✗」是假红（它报的是「上限失效」，
 * 而上限根本没被调用到）。
 *
 * 客户端侧**没有**干净的办法把这个绑定摘掉再重来：storage 清得掉，但 pinia
 * 只在启动时水合，内存里那份还在，下一次变更又原样写回去；而开发者工具的定位
 * 没有命令行开关，自动化连上时那一次启动已经跑完了。
 *
 * 所以按「只测这一层测得了的东西」收口：
 *   · 客户端独有的那半 —— 冷启动能不能自己绑上、要货带不带 communityNo —— 留在 ① ；
 *   · 距离分档是**服务端规则**，在 `CoarseLocationRegionPoolTest` 判据 3、4 里
 *     有真覆盖（19 公里绑得上 / 550 公里只给距离不给归属）。这里只复验
 *     「从小程序这条真网络打过去，服务端给的是那个答案」，不再声称客户端换了位置。
 */
async function resolveAt(where) {
  const latE6 = Math.round(where.latitude * 1e6);
  const lngE6 = Math.round(where.longitude * 1e6);
  const r = await mp.evaluate((la, ln) => new Promise((res) => {
    wx.request({
      url: `https://www.hxmall.top/mp/location/resolve?latE6=${la}&lngE6=${ln}&coarse=true`,
      success: (x) => res(JSON.stringify(x.data)),
      fail: (e) => res("fail: " + (e && e.errMsg)),
      });
    setTimeout(() => res("超时"), 8000);
  }), latE6, lngE6);
  const body = JSON.parse(String(r));
  if (body.code !== 0) throw new Error("resolve 没返回成功：" + String(r).slice(0, 120));
  return body.data;
}

// ② M6：围栏外 19 公里 —— 服务端要给出最近的聚落
const longhua = await resolveAt(LONGHUA);
if (longhua.nearestNo) {
  ok(`龙华（围栏外 19 公里）服务端给了最近聚落：${longhua.nearestNo}（${longhua.nearestDistanceM} 米）`);
} else {
  fail(`龙华没给最近聚落：${JSON.stringify(longhua)} —— M6 那一级没接上`);
}

// ③ 对照：超出上限 —— **不许**给归属，否则就是把 1940 公里外的货说成「你这儿的」
const beijing = await resolveAt(BEIJING);
if (beijing.nearestNo) {
  fail(`北京也给了归属（${beijing.nearestNo}）—— 上限没生效，那些货送不到他那儿`);
} else {
  ok(`北京没给归属（距离 ${beijing.nearestDistanceM} 米，超出上限）`);
}

/*
 * **一近一远同时成立才叫「按距离分档」。**
 * 只验龙华绑上，「不设上限」也会绿；只验北京没绑，「什么都不绑」也会绿。
 */
if (longhua.nearestNo && !beijing.nearestNo) {
  ok("对照成立：19 公里绑得上、1940 公里绑不上");
}

console.log(bad ? `\n✗ ${bad} 条不对` : "\n✓ 全部通过");
try { await mp.disconnect(); } catch { /* ignore */ }
process.exit(bad ? 1 : 0);
