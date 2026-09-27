/**
 * 小程序真实链路：**运城盐湖区的买家看得到虹选粮油，结算页能选「当面付款」**。
 *
 *   npm run build:mp-weixin          # 指向生产
 *   node tests/e2e/mp-offline-pay.mjs
 *
 * <p>走的全是买家会走的界面：首页顶栏进「选择位置」（浏览模式）→ 重新定位 → 使用当前位置
 * → 回首页看货 → 商品详情 → 立即购买 → 结算页。**不提交订单**（那会在生产留一张真单）。
 *
 * <p>位置用 `mockWxMethod` 换成运城市政府一带。它在**连上之后**才生效
 * （冷启动时的自动绑定已经按工具里的真实定位做完了，见 memory mp-devtools-automation），
 * 所以这里不靠启动流程，而是在界面上点「重新定位」—— 那一次 getLocation 走的是 mock。
 *
 * <p><b>会改买家的一条服务端数据</b>：「使用当前位置」会把 `usr_account.community_no`
 * 绑到运城那边的聚落。跑完用同一条路切回原来的位置（龙华那个聚落的坐标），
 * 并从生产库回读确认已还原 —— 回读不一致就红。
 *
 * <p>判据：
 *   · 首页文本里有「五得利」—— 按位置看货真的看到了（不是按商家号查）
 *   · 结算页出现「支付方式」块且有「当面付款」—— 那一块**只在能选时才画**
 *   · 点「当面付款」后选中态落在它上面
 */
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, execFileSync } from "node:child_process";
import { createRequire } from "node:module";

const nodeRequire = createRequire(import.meta.url);
nodeRequire("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};
const automator = nodeRequire("miniprogram-automator");

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = resolve(HERE, "../../dist/build/mp-weixin");
const OUT = resolve(HERE, "out");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);

/** 运城市政府一带（盐湖区城区） */
const YUNCHENG = { latitude: 35.026, longitude: 111.007 };
/** 虹选粮油的一件在售商品：五得利 五星特精小麦粉 */
const GOODS_NO = process.env.GOODS_NO || "G202609271616250006820";
/** 这台工具登录的微信对应的买家；跑完要把他的归属还原 */
const BUYER_PHONE = process.env.BUYER_PHONE || "18503088359";
/** STAY=1：不切位置，就用买家现在的归属看货（门店范围覆盖了买家所在的城市时用） */
const STAY = process.env.STAY === "1";

mkdirSync(OUT, { recursive: true });
const ok = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => { console.error(`  ✗ ${m}`); process.exitCode = 1; };
const info = (m) => console.log(`  · ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function cli(args) {
  return new Promise((res) => {
    const p = spawn(CLI, args, { stdio: ["ignore", "ignore", "ignore"] });
    p.on("close", res);
  });
}

async function connect() {
  await cli(["open", "--project", PROJECT]);
  await cli(["auto", "--project", PROJECT, "--auto-port", String(PORT)]);
  for (let i = 0; i < 15; i++) {
    try {
      return await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
    } catch {
      await sleep(2000);
    }
  }
  throw new Error("连不上开发者工具的自动化端口");
}

/** 生产库里这个买家当前绑的聚落 —— 端上看不到「服务端存的是什么」 */
function serverCommunity() {
  const sql = `SELECT IFNULL(community_no,'') FROM ai_shop.usr_account WHERE phone='${BUYER_PHONE}'`;
  return execFileSync("ssh", ["soukmind-tx-root",
    `mysql -S /run/mysqld97/mysqld.sock -N -e "${sql}" 2>/dev/null`]).toString().trim();
}

/** 聚落坐标：还原时把定位 mock 回去，走同一条「使用当前位置」 */
function communityCoords(no) {
  const sql = `SELECT lat_e6, lng_e6 FROM ai_shop.cmt_community WHERE community_no='${no}'`;
  const [lat, lng] = execFileSync("ssh", ["soukmind-tx-root",
    `mysql -S /run/mysqld97/mysqld.sock -N -e "${sql}" 2>/dev/null`]).toString().trim().split(/\s+/);
  return { latitude: Number(lat) / 1e6, longitude: Number(lng) / 1e6 };
}

/** 聚落挂在哪个区划下（判「是不是真的切到了运城」） */
function regionOf(no) {
  if (!no) return "";
  const sql = `SELECT IFNULL(region_code,'') FROM ai_shop.cmt_community WHERE community_no='${no}'`;
  return execFileSync("ssh", ["soukmind-tx-root",
    `mysql -S /run/mysqld97/mysqld.sock -N -e "${sql}" 2>/dev/null`]).toString().trim();
}

/** reLaunch 的返回值常抛 rawPath（memory 第 4 条）；跳完自己等页面路径对上 */
async function go(mp, url, retried = false) {
  // 首页是 tab 页只能 reLaunch；其余用 navigateTo —— reLaunch 到普通页在这版工具上常常落不了地
  const tab = url.startsWith("/pages/home/");
  try {
    await (tab ? mp.reLaunch(url) : mp.navigateTo(url));
  } catch {
    // 跳转其实成功了
  }
  const path = url.split("?")[0].replace(/^\//, "");
  for (let i = 0; i < 20; i++) {
    try {
      const p = await mp.currentPage();
      if (p && p.path === path) return p;
    } catch {
      // 页面栈还在切换
    }
    await sleep(500);
  }
  // 没落地：多半是页面栈满了（上几轮留下的详情页、结算页，小程序栈上限 10 层）。
  // 回首页清栈再跳一次；还不行才算失败
  if (!tab && !retried) {
    try {
      await mp.reLaunch("/pages/home/index");
    } catch {
      // 跳转其实成功了
    }
    await sleep(2500);
    return go(mp, url, true);
  }
  throw new Error(`没跳到 ${url}`);
}

async function page(mp) {
  for (let i = 0; i < 10; i++) {
    try {
      const p = await mp.currentPage();
      if (p) return p;
    } catch {
      // 同上
    }
    await sleep(500);
  }
  throw new Error("拿不到当前页");
}

/**
 * 页面可见文本（小程序没有 innerText，逐节点收）。
 * **自定义组件里面要单独进去收**：首页的商品卡在 `<biz-goods-card>` 里，
 * `page.$$("text")` 看不到组件内部 —— 第一版就是截图上四件货都在、断言说一件没有。
 */
const COMPONENTS = ["biz-goods-card"];
async function textOf(p) {
  const out = [];
  const grab = async (nodes) => {
    for (const n of nodes) {
      try {
        const t = await n.text();
        if (t && t.trim()) out.push(t.trim());
      } catch {
        // 收集途中被重渲染掉了
      }
    }
  };
  await grab(await p.$$("text"));
  for (const tag of COMPONENTS) {
    for (const c of await p.$$(tag)) {
      try {
        await grab(await c.$$("text"));
      } catch {
        // 组件已卸载
      }
    }
  }
  return out.join(" | ");
}

/**
 * 点**最后一个**文字完全等于 label 的节点（底栏与弹层重名时，弹层在后）。
 * 连 `view` 一起找：按钮常常是一个直接写字的 view（「立即购买」就是）。
 */
async function tapText(p, label) {
  let hit = null;
  for (const n of await p.$$("text, view")) {
    try {
      if ((await n.text()).trim() === label) hit = n;
    } catch {
      // 跳过
    }
  }
  if (!hit) throw new Error(`页面上没有「${label}」`);
  await hit.tap();
}

async function waitText(mp, needle, ms = 12000) {
  const end = Date.now() + ms;
  let last = "";
  while (Date.now() < end) {
    last = await textOf(await page(mp));
    if (last.includes(needle)) return last;
    await sleep(800);
  }
  return last;
}

/** 用小程序自己的会话调一次接口（购物车清理用） */
async function mpApi(mp, method, path, data) {
  return mp.evaluate((m, p, d) => new Promise((res) => {
    const t = wx.getStorageSync("shcr_token");
    const tok = typeof t === "string" && t.startsWith("\"") ? JSON.parse(t) : t;
    wx.request({ url: `https://www.hxmall.top${p}`, method: m, data: d,
      header: { Authorization: "Bearer " + tok },
      success: (r) => res(r.data), fail: (e) => res({ err: e && e.errMsg }) });
  }), method, path, data);
}

/**
 * 浏览模式选位置：重新定位（走 mock 的坐标）→ 等「当前位置」那张卡换成目标一带 → 点「使用」。
 * 不等就点的话，用的是卡上还没刷新的旧位置 —— 看起来切过了，其实一动没动。
 */
async function moveTo(mp, at, expect, label, settled) {
  await mp.mockWxMethod("getLocation", { ...at, accuracy: 30, errMsg: "getLocation:ok" });
  await mp.mockWxMethod("getFuzzyLocation", { ...at, errMsg: "getFuzzyLocation:ok" });
  const p = await go(mp, "/pages/address-pick/index?mode=browse");
  await sleep(1500);
  /*
   * **只看「当前位置」那张卡，且要求它变了**。第二版看整页文字：页面下方列着买家的收货地址
   * （「广东省深圳市…」），于是「等出现深圳」一进页面就成立 —— 没等新坐标回来就点了「使用」，
   * 用的是上一次的坐标，买家被留在了运城。
   */
  const cardText = async () => {
    const el = await (await page(mp)).$(".hererow");
    return el ? (await el.text()).trim() : "";
  };
  const before = await cardText();
  await tapText(p, "重新定位");
  let card = "";
  for (let i = 0; i < 20; i++) {
    card = await cardText();
    if (card && card !== before && card.includes(expect)) break;
    await sleep(700);
  }
  if (!card.includes(expect)) throw new Error(`重新定位后「当前位置」没变成${label}：${card.slice(0, 120)}`);
  await tapText(await page(mp), "使用");
  // **等服务端真的换了归属再往下走**。第一版固定等 3 秒：绑定是异步的（附近聚落 → 解析 → 绑定三跳），
  // 3 秒时还是旧值，首页按旧城市要货；更糟的是它在「还原」之后才落地，把买家留在了运城。
  const want = typeof settled === "function" ? settled : (no) => no === settled;
  for (let i = 0; i < 30; i++) {
    if (want(serverCommunity())) break;
    await sleep(1000);
  }
  info(`已切到${label}（服务端归属 ${serverCommunity()}）`);
}

// ---------------------------------------------------------------- 跑

const mp = await connect();
const before = serverCommunity();
info(`买家 ${BUYER_PHONE} 原归属：${before || "（空）"}`);
const home = before ? communityCoords(before) : null;
/** 「立即购买」会先加购；跑完只删这次加进去的那一行，买家原有的购物车不动 */
const cartBefore = ((await mpApi(mp, "GET", "/mp/cart")).data || []).map((x) => x.skuNo);

try {
  // ① 请求发得出去 —— 否则下面每一条都说明不了问题
  const probe = await mp.evaluate(() => new Promise((res) => {
    wx.request({
      url: "https://www.hxmall.top/mp/location/resolve?latE6=35026000&lngE6=111007000",
      success: (r) => res(JSON.stringify(r.data)),
      fail: (e) => res("fail: " + (e && e.errMsg)),
    });
    setTimeout(() => res("超时"), 10000);
  }));
  if (!String(probe).includes('"regionCode":"140802"')) {
    fail(`小程序里请求不到生产或解析不出盐湖区：${String(probe).slice(0, 160)}`);
    throw new Error("前置不成立");
  }
  ok("小程序连得上生产，运城坐标解析为盐湖区（140802）");

  // ② 买家把位置切到运城（STAY=1 时跳过，按现有归属看货）
  if (!STAY) await moveTo(mp, YUNCHENG, "运城", "运城盐湖区", (no) => regionOf(no).startsWith("140802"));
  const bound = serverCommunity();
  info(`切换后服务端归属：${bound || "（空）"}`);

  // ③ 首页按位置看货。
  // 商品卡在 <biz-goods-card> 组件里，automator 的选择器进不去（page.$$ / 组件 $$ 都是 0）——
  // 所以断言走「小程序自己按**当前归属**要一次首页那个列表」，参数与首页 home/index.vue 同一口径
  // （有归属带 communityNo），截图作为人眼证据一并落盘。
  await go(mp, "/pages/home/index");
  await sleep(5000);
  await mp.screenshot({ path: resolve(OUT, "offline-pay-1-home.png") });
  const listed = await mp.evaluate(() => new Promise((res) => {
    const raw = wx.getStorageSync("shcr_community");
    const c = raw ? (typeof raw === "string" ? JSON.parse(raw) : raw).community : null;
    const q = c ? `communityNo=${c.communityNo}` : "";
    wx.request({
      url: `https://www.hxmall.top/mp/goods?size=20&${q}`,
      success: (r) => res(JSON.stringify({
        c: c && c.name,
        titles: ((r.data.data || {}).records || []).map((g) => `${g.title}@${(g.merchant || {}).name}`),
      })),
      fail: (e) => res(JSON.stringify({ err: e && e.errMsg })),
    });
  }));
  const L = JSON.parse(String(listed));
  info(`首页归属：${L.c}；列表 ${JSON.stringify(L.titles)}`);
  if ((L.titles || []).some((t) => t.includes("五得利") && t.includes("虹选粮油"))) {
    ok("首页按当前归属要货，列表里有虹选粮油的五得利面粉（截图 offline-pay-1-home.png）");
  } else {
    fail("首页列表里没有虹选粮油");
  }

  // ④ 详情 → 立即购买 → 规格面板里再点「立即购买」
  // 面板底部那颗按钮在面板打开后才渲染（v-if），要重新查
  await go(mp, `/pages/goods/index?goodsNo=${GOODS_NO}`);
  await waitText(mp, "五星特精", 12000);
  const bar = await (await page(mp)).$$(".actionbar__buy");
  if (!bar.length) throw new Error("详情页没有「立即购买」");
  await bar[bar.length - 1].tap();
  await sleep(1500);
  const inSheet = await (await page(mp)).$$(".sheetbar .actionbar__buy");
  if (inSheet.length) await inSheet[inSheet.length - 1].tap();
  await sleep(3500);
  const c = await page(mp);
  info(`当前页：${c.path}`);
  const confirmText = await waitText(mp, "支付方式", 12000);
  await mp.screenshot({ path: resolve(OUT, "offline-pay-2-confirm.png") });
  if (c.path !== "pages/order-confirm/index") fail(`没进结算页（在 ${c.path}）`);
  if (confirmText.includes("支付方式") && confirmText.includes("当面付款")) {
    ok("结算页出现「支付方式」，可选「当面付款」");
  } else {
    fail(`结算页没有当面付款：${confirmText.slice(0, 300)}`);
  }
  /*
   * **能选当面付款 ≠ 下得了单。** 第一次跑就是这样绿的：截图上顶着一行红字
   * 「虹选粮油 在你这一带没有可用的取货点」—— 深圳的买家看得到运城一家只做门店自提的店，
   * 结算页也画得出来，但提交会被拦。拦单的提示出现就算红。
   */
  //
  // 判据用**类名**不用文案：第二版按「没有可用的取货点」匹配，文案一改成「附近暂无取货点」
  // 就假绿了 —— 截图上红字还在。recv__warn 同时承载「距你约 X 公里」（提醒、不拦单），要排掉。
  const warns = [];
  for (const n of await (await page(mp)).$$(".recv__warn")) {
    const t = (await n.text()).trim();
    if (t && !/^距你约/.test(t)) warns.push(t);
  }
  if (warns.length) fail(`结算页有拦单提示：${warns.join("；")}`);
  else ok("结算页没有拦单提示");

  // ⑤ 选当面付款：以选中态为准（sh-chip--primary 落到「当面付款」那一颗上）
  await tapText(await page(mp), "当面付款");
  await sleep(1500);
  let picked = "";
  for (const m of await (await page(mp)).$$(".paymode")) {
    const cls = String((await m.attribute("class")) || "");
    if (cls.includes("sh-chip--primary")) picked = (await m.text()).trim();
  }
  await mp.screenshot({ path: resolve(OUT, "offline-pay-3-offline-picked.png") });
  if (picked === "当面付款") ok("选中当面付款（未提交订单）");
  else fail(`点了当面付款，选中的却是「${picked}」`);
} finally {
  const added = ((await mpApi(mp, "GET", "/mp/cart")).data || [])
    .map((x) => x.skuNo).filter((k) => !cartBefore.includes(k));
  if (added.length) {
    await mpApi(mp, "POST", "/mp/cart/remove", { skuNos: added });
    info(`已从购物车移除本次加入的 ${added.length} 行`);
  }
  // ⑥ 还原买家位置：同一条路切回原聚落的坐标，再从库回读
  if (home && !STAY) {
    try {
      await moveTo(mp, home, "深圳", "原来的位置", before);
      const now = serverCommunity();
      if (now === before) ok(`买家归属已还原（${now}）`);
      else fail(`买家归属没还原：原 ${before}，现 ${now}`);
    } catch (e) {
      fail(`还原位置失败：${e.message}`);
    }
  }
  mp.disconnect();
}
