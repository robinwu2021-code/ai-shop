/**
 * 小程序真实链路：**商品从看得见到进购物车**。
 *
 *   node tests/e2e/mp-goods-flow.mjs
 *
 * <p>覆盖 首页列表 → 详情 → 切规格 → 加购 → 购物车 五步。
 *
 * <p><b>为什么这条链路值得用真机跑，而不是只写组件测试。</b>
 * 组件测试给的是**它自己造的 props**；这里问的是另一件事 ——
 * 真后端回的那份数据，端上认不认得。这一天里的返工全长这样：
 * 接口通、字段对、页面空白（`v-else-if` 顺序、空态挡住列表、CSS 变量拼错）。
 * 那类缺陷 curl 一次也测不出来，因为 curl 看的是 JSON，用户看的是屏幕。
 *
 * <p>另外它顺带盯一件今天刚修的事：**零评价不能显示满分**。
 * 后端对没人评过的商家回 `rating: 5.0`，那是默认值不是好评。
 * 静态守卫（rating-guard.test.ts）能挡住新写的裸 `<sh-rating>`，
 * 但挡不住「守卫放行了、渲染出来还是 5.0」—— 只有真页面能回答。
 */
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

// 见 mp-store-visible.mjs 里那段长注释：2026 版工具没有 SDKVersion 字段，校验必炸
const nodeRequire = createRequire(import.meta.url);
nodeRequire("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};
const automator = nodeRequire("miniprogram-automator");

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = resolve(HERE, "../../dist/build/mp-weixin");
const OUT = resolve(HERE, "out");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);
/** 演示社区在杭州西湖区；模拟器默认北京，5 公里的闸会把它们全滤掉（那是对的行为） */
const AT = { latitude: 30.28, longitude: 120.1, errMsg: "getLocation:ok" };

mkdirSync(OUT, { recursive: true });
const steps = [];
const ok = (m) => (steps.push(`  ✓ ${m}`), console.log(`  ✓ ${m}`));
const fail = (m) => (steps.push(`  ✗ ${m}`), console.error(`  ✗ ${m}`), (process.exitCode = 1));
const info = (m) => (steps.push(`  · ${m}`), console.log(`  · ${m}`));

function enableAutomation() {
  return new Promise((res, rej) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.stderr.on("data", (d) => (out += d));
    p.on("close", (c) => (c === 0 ? res(out) : rej(new Error(`cli auto 退出码 ${c}：${out.slice(-400)}`))));
  });
}

async function connectWithRetry(tries = 15) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      return await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  throw last;
}

/** 页面可见文本。小程序没有 innerText，只能逐节点收 */
async function textOf(page) {
  const out = [];
  for (const n of await page.$$("text, view, button")) {
    try {
      const t = await n.text();
      if (t && t.trim()) out.push(t.trim());
    } catch {
      // 收集途中节点被重渲染掉了 —— 跳过它，别让整轮挂掉
    }
  }
  return out.join(" | ");
}

/** 轮询直到出现 needle。返回最后读到的文本 —— 失败时它就是证据 */
async function waitForText(page, needle, ms = 15000) {
  const until = Date.now() + ms;
  let t = "";
  while (Date.now() < until) {
    t = await textOf(page);
    if (t.includes(needle)) return t;
    await page.waitFor(700);
  }
  return t;
}

/**
 * 轮询等一个元素出现。
 *
 * <p><b>一次性 `page.$()` 是不够的。</b> 上一轮就栽在这：点开社区后立刻查 `.pk`，
 * 查不到就断言「没有自提点行」——而页面文本证明它其实渲染出来了，只是晚了几百毫秒。
 * 那种报错最坑人：它把「我问早了」说成「功能坏了」，会把人引去查后端。
 */
async function waitForEl(page, sel, ms = 8000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const el = await page.$(sel).catch(() => null);
    if (el) return el;
    await page.waitFor(400);
  }
  return null;
}

async function waitForEls(page, sel, ms = 12000) {
  const until = Date.now() + ms;
  let els = [];
  while (Date.now() < until) {
    els = await page.$$(sel).catch(() => []);
    if (els.length) return els;
    await page.waitFor(500);
  }
  return els;
}

async function shot(name) {
  try {
    await miniProgram.screenshot({ path: resolve(OUT, `${name}.png`) });
  } catch {
    /* 截图失败不该盖住真正的断言失败 */
  }
}

/** 跳页并自己轮询落地页。不信 reLaunch 的返回值 —— 见 mp-store-visible.mjs */
async function goto(path) {
  const want = path.replace(/^\//, "").split("?")[0];
  await miniProgram.reLaunch(path).catch(() => {});
  let landed = null;
  for (let i = 0; i < 15; i++) {
    await new Promise((r) => setTimeout(r, 800));
    try {
      landed = await miniProgram.currentPage();
      if (landed && landed.path === want) return landed;
    } catch {
      /* 正在切页，下一轮再问 */
    }
  }
  if (landed) {
    info(`想去 ${want}，实际停在 ${landed.path}`);
    return landed;
  }
  throw new Error(`跳不到 ${path}，且拿不到当前页`);
}

let miniProgram;
try {
  console.log(`[e2e] 启用自动化端口 ${PORT}`);
  await enableAutomation();
  miniProgram = await connectWithRetry();
  await miniProgram.mockWxMethod("getLocation", AT);
  await miniProgram.mockWxMethod("showModal", { confirm: true, cancel: false });

  // 0) 用界面绑社区。**不往 storage 里塞** —— pinia 在启动时水合，
  //    跑起来之后改 storage 不回灌；而且那样测到的是用户走不到的状态。
  let page = await goto("/pages/community/index");
  await page.waitFor(2500);
  /*
   * **已经绑过就不再绑。** 归属是持久化的，第二次跑时这一页会直接把人送回首页 ——
   * 那是对的行为。上一轮把它当成「选社区页没有社区行」报了红，
   * 而真实情况是「这一步根本不需要做」。
   */
  if (page.path !== "pages/community/index") {
    ok(`已有社区归属（这一页把我送回了 ${page.path}）`);
  } else {
  const head = await waitForEl(page, ".cm__head", 15000);
  if (head) {
    await head.tap();
    const pk = await waitForEl(page, ".pk", 8000);
    if (pk) {
      await pk.tap();
      await page.waitFor(2500);
      ok("已绑定社区自提点");
    } else fail("社区展开了，但没有自提点行");
  } else fail(`选社区页没有社区行：${(await textOf(page)).slice(0, 200)}`);
  }

  /*
   * 1) 首页商品卡。
   *
   * <p><b>选 `.freq__i` 而不是 `.card`。</b> 「社区在卖」那一栏用的是
   * `biz-goods-card` 自定义组件 —— <b>automator 的 `page.$$` 穿不进自定义组件内部</b>，
   * `.card`、`>>> .card`、`page >>> .card` 全都返回 0，而页面文本里商品明明都在。
   * 那种「元素数 0 但文本有」的组合最容易被读成「没渲染」，实际是选择器够不着。
   * 「推荐商品」这一栏写在首页自己的模板里，选得到、点得动，走的是同一个
   * `openGoods()`，测的是同一条路。
   */
  page = await goto("/pages/home/index");
  await page.waitFor(1200);
  const cards = await waitForEls(page, ".freq__i", 15000);
  await shot("g1-home");
  if (cards.length === 0) {
    fail(`首页没有商品卡。页面文本：${(await textOf(page)).slice(0, 300)}`);
    throw new Error("首页没有商品卡，后面几步没有意义");
  }
  ok(`首页有 ${cards.length} 张商品卡`);

  // 2) 点第一张进详情
  const title = await (await cards[0].$(".freq__title"))?.text().catch(() => "");
  await cards[0].tap();
  await page.waitFor(2500);
  page = await miniProgram.currentPage();
  if (page.path !== "pages/goods/index") {
    fail(`点商品卡没进详情，停在 ${page.path}`);
    throw new Error("进不去详情");
  }
  const detail = await waitForText(page, "¥", 15000);
  await shot("g2-goods");
  ok(`进入详情：${title || "(标题未取到)"}`);

  // 3) 价格与规格
  const price = await (await page.$(".price"))?.text().catch(() => "");
  if (/\d/.test(price)) ok(`详情价格渲染：${price.replace(/\s+/g, " ").slice(0, 40)}`);
  else fail(`详情没有价格。页面文本：${detail.slice(0, 300)}`);

  let chosenSpec = "";
  const specs = await waitForEls(page, ".spec", 6000);
  if (specs.length >= 2) {
    /*
     * **切规格要验价格真的跟着变。**
     * 「点得动」和「点了有用」是两回事：多规格共用同一份价格这个 bug
     * 上周刚修过一次（见 9186c493），当时页面同样点得动、同样不报错。
     */
    const before = price;
    chosenSpec = (await specs[1].text().catch(() => "")).trim();
    await specs[1].tap();
    await page.waitFor(1200);
    const after = await (await page.$(".price"))?.text().catch(() => "");
    await shot("g3-spec");
    if (after && after !== before) ok(`切规格价格随动：${before.trim()} → ${after.trim()}`);
    else info(`切规格后价格没变（${after.trim()}）—— 若两规格同价属正常，需人工确认`);
  } else info(`只有 ${specs.length} 个规格可选，跳过切换验证`);

  // 4) 零评价不能显示满分（今天刚修的那条规则，在真页面上复验）
  if (/5\.0/.test(detail) && /暂无评价|0 条评价/.test(detail)) {
    fail(`详情同时出现「5.0」和「暂无评价」—— 零评价仍在显示满分`);
  } else ok("详情没有出现「零评价却满分」");

  // 5) 加购
  const add = await waitForEl(page, ".actionbar__add", 8000);
  if (!add) fail("详情没有加购按钮");
  else {
    await add.tap();
    await page.waitFor(2000);
    const badge = await (await page.$(".actionbar__badge"))?.text().catch(() => "");
    await shot("g4-added");
    if (badge && Number(badge) > 0) ok(`加购成功，购物车角标 = ${badge}`);
    else fail(`加购后角标没出现（读到 "${badge}"）—— 要么没加进去，要么加了不显示`);
  }

  // 6) 购物车里要真有这件
  page = await goto("/pages/cart/index");
  const cartText = await waitForText(page, "¥", 12000);
  await shot("g5-cart");
  /*
   * **按文本断言，不数 `.seg` 元素。** 购物车的行同样在自定义组件里，
   * `page.$$(".seg")` 恒为 0 —— 上一轮据此报「购物车是空的」，
   * 而紧接着的文本断言证明商品就在里面。两条互相打架的断言里，错的是数元素那条。
   */
  if (title && cartText.includes(title)) ok(`购物车里认得出「${title}」`);
  else fail(`购物车里找不到「${title || "刚加的商品"}」。文本：${cartText.slice(0, 300)}`);

  // 切过规格就要带着切后的那个进来，不能悄悄换回默认规格
  if (chosenSpec && cartText.includes(chosenSpec)) ok(`购物车带的是切换后的规格「${chosenSpec}」`);
  else if (chosenSpec) fail(`购物车里的规格不是「${chosenSpec}」。文本：${cartText.slice(0, 200)}`);

  if (/去结算/.test(cartText)) ok("结算入口在");
  else fail(`购物车没有结算入口。文本：${cartText.slice(0, 200)}`);
} catch (e) {
  const raw = String(e?.message ?? e);
  if (/http port is open|ECONNREFUSED/i.test(raw)) {
    fail(
      "连不上开发者工具。要在**界面里**做两件事（命令行改不了）：\n" +
        "     1. 用微信扫码登录开发者工具\n" +
        "     2. 设置 → 安全设置 → 服务端口（CLI/HTTP 调用）→ 打开",
    );
  } else fail(`跑挂了：${raw}`);
} finally {
  // disconnect 不是 close：close 会把项目窗口一起关掉，下次跑会伪装成「连不上」
  if (miniProgram) await (miniProgram.disconnect?.() ?? Promise.resolve()).catch(() => {});
  console.log("\n[e2e] 结果");
  console.log(steps.join("\n"));
  console.log(`[e2e] 截图在 ${OUT}`);
}
