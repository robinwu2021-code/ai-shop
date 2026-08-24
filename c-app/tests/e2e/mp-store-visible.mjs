/**
 * 小程序真机链路自动化：**C 端能不能看到门店与它的商品**。
 *
 * 跑法（先在开发者工具里扫码登录一次，且「设置 → 安全设置 → CLI/HTTP 调用」打开）：
 *   node tests/e2e/mp-store-visible.mjs "老张粮油店" "测试商品名"
 *
 * 为什么用它而不是只 curl 接口：接口通 ≠ 页面看得见。
 * 这一天里三次返工全是「数据对、接口对、页面没渲染」——
 * `v-else-if` 顺序、空态挡住区域列表、CSS 变量拼错，curl 一次都测不出来。
 *
 * 失败会截图到 tests/e2e/out/，因为「断言没通过」本身说不清页面当时长什么样。
 */
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";

/*
 * **绕过 automator 的版本校验。**
 *
 * `MiniProgram.checkVersion()` 调 `Tool.getInfo` 读 **`SDKVersion`** 字段，
 * 而 2026 版开发者工具回的是 `{"version":"2.02.2608040"}` —— 没有那个字段。
 * 于是 `cmpVersion(undefined, "2.7.3")` 在 `undefined.split(...)` 上炸，
 * 报错是「Cannot read properties of undefined」，跟版本、跟连接都看不出关系，
 * 而在此之前 `launch()` 还会先静默等满超时。查了四轮才落到这一行。
 *
 * 这层校验本身只想确认「工具 ≥ 2.7.3」，我们的是 2.02.2608040（2026 年），
 * 远超要求。automator 最新发布版是 0.12.1（2020 年），不会再修了。
 */
const nodeRequire = createRequire(import.meta.url);
nodeRequire("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = resolve(HERE, "../../dist/build/mp-weixin");
const OUT = resolve(HERE, "out");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);

/** 跑 `cli auto` 打开自动化端口。已经开着时它直接成功返回 */
function enableAutomation() {
  return new Promise((res, rej) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.stderr.on("data", (d) => (out += d));
    p.on("close", (code) =>
      code === 0 ? res(out) : rej(new Error(`cli auto 失败（退出码 ${code}）：\n${out.slice(-400)}`)),
    );
  });
}

const [storeName = "老张粮油店", goodsName = ""] = process.argv.slice(2);
const COMMUNITY_NO = process.env.MP_COMMUNITY || "C0001";
const PICKUP_NO = process.env.MP_PICKUP || "PP0001";
/** `shc`(VITE_APP_NS) + `r`(真后端) + `_community`，见 shared/utils/constants STORAGE */
const COMMUNITY_KEY = process.env.MP_COMMUNITY_KEY || "shcr_community";

mkdirSync(OUT, { recursive: true });

const steps = [];
function ok(msg) {
  steps.push(`  ✓ ${msg}`);
  console.log(`  ✓ ${msg}`);
}
function fail(msg) {
  steps.push(`  ✗ ${msg}`);
  console.error(`  ✗ ${msg}`);
  process.exitCode = 1;
}

/** 页面上所有可见文本。小程序没有 innerText，只能把节点文本收集起来 */
async function textOf(page) {
  const nodes = await page.$$("text, view, button");
  const out = [];
  for (const n of nodes) {
    try {
      const t = await n.text();
      if (t && t.trim()) out.push(t.trim());
    } catch {
      // 节点在收集过程中被重渲染掉了 —— 跳过它，不要让整轮挂掉
    }
  }
  return out.join(" | ");
}

/** 轮询页面文本，直到出现 needle 或超时。返回最后一次读到的文本 */
async function waitForText(page, needle, ms) {
  const until = Date.now() + ms;
  let text = "";
  while (Date.now() < until) {
    text = await textOf(page);
    if (text.includes(needle)) return text;
    await page.waitFor(700);
  }
  return text;
}

async function shot(page, name) {
  try {
    await page.waitFor(300);
    await miniProgram.screenshot({ path: resolve(OUT, `${name}.png`) });
  } catch {
    /* 截图失败不该盖住真正的断言失败 */
  }
}

/** 连 ws，最多等 ~30 秒。端口是 cli auto 刚开的，起来需要一两秒 */
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

/**
 * 跳到某一页并拿到 page 对象。
 *
 * <p><b>不用 `reLaunch()` 的返回值。</b> 2026 版工具下它常抛
 * `Cannot destructure property 'rawPath' of ... as it is null` ——
 * 跳转其实成功了，只是它取页面元信息时那一刻 webview 还没挂上。
 * 跳完自己轮询 `currentPage()`，比信它的返回值稳。
 */
async function goto(path) {
  const want = path.replace(/^\//, "");
  await miniProgram.reLaunch(path).catch(() => {});
  let landed = null;
  for (let i = 0; i < 15; i++) {
    await new Promise((r) => setTimeout(r, 800));
    try {
      landed = await miniProgram.currentPage();
      if (landed && landed.path === want) return landed;
    } catch {
      // 页面正在切，下一轮再问
    }
  }
  /*
   * **落到别的页也要往下走，不要抛。**
   * 应用自己会改道（未登录去登录页、未绑社区去选社区页），那是被测行为的一部分；
   * 抛掉的话报错是「跳不到 X」，而真正该说的是「它把我带去了 Y」——
   * 后者一眼能看出是重定向，前者看起来像自动化坏了。
   */
  if (landed) {
    console.log(`  · 想去 ${want}，实际停在 ${landed.path}`);
    return landed;
  }
  throw new Error(`跳不到 ${path}，且拿不到当前页`);
}

let miniProgram;
try {
  console.log(`[e2e] 启动开发者工具，项目 ${PROJECT}`);
  /*
   * **用 connect 而不是 launch。**
   *
   * `automator.launch()` 自己去跑 `cli auto`，再从它的**标准输出里抠 ws 地址**。
   * 这版开发者工具（2.02.2608040）成功时只打印 `✔ auto`、不打印地址，
   * 于是 automator 炸在 `undefined.split(...)` —— 报错跟真正的原因毫无关系，
   * 而在那之前它还会静默等满超时。
   *
   * 自己跑 `cli auto --auto-port`，端口是我们指定的，连它就行。
   * 附带好处：`cli auto` 的输出看得见（AppID、权限、失败原因），launch 会把它吞掉。
   */
  console.log(`[e2e] 启用自动化端口 ${PORT}`);
  const autoOut = await enableAutomation();
  console.log(autoOut.split("\n").filter((l) => l.includes("AppID")).join("\n") || "");
  /*
   * **连接要重试。** `cli auto` 退出时 ws 服务还没起完 ——
   * 立刻 connect 会得到 "check if target project window is opened"，
   * 那句话把人引向「是不是没开窗口」，而实际上再等两秒就好了。
   */
  miniProgram = await connectWithRetry();

  /*
   * **用应用自己的路绑定社区，而不是往 storage 里塞。**
   *
   * 塞 storage 试过了：写进去了，页面还是空 —— 因为 pinia 是在**小程序启动时**
   * 水合的，跑起来之后再改 storage 不会回灌到 store 里。
   * 而且就算能塞，塞出来的状态也绕过了「选社区 → 绑定」这条真实路径，
   * 测到的是一个用户永远走不到的状态。
   *
   * 定位用 mock：模拟器默认在北京，而演示社区在杭州西湖区，
   * 半径 5 公里的闸会把它们全滤掉（那是对的行为，不是 bug）。
   */
  await miniProgram.mockWxMethod("getLocation", {
    latitude: 30.28,
    longitude: 120.1,
    errMsg: "getLocation:ok",
  });
  // 切换自提点会弹二次确认，让它一律「确定」
  await miniProgram.mockWxMethod("showModal", { confirm: true, cancel: false });

  let page = await goto("/pages/community/index");
  await page.waitFor(2500);
  await shot(page, "0-community");

  const head = await page.$(".cm__head").catch(() => null);
  if (head) {
    await head.tap();               // 展开第一个社区
    await page.waitFor(1200);
    const pk = await page.$(".pk").catch(() => null);
    if (pk) {
      await pk.tap();               // 选中它的第一个自提点
      await page.waitFor(2500);
      ok("已通过界面绑定社区与自提点");
    } else {
      fail("社区展开了，但里面没有自提点行");
    }
  } else {
    const t = await textOf(page);
    fail(`选社区页没有社区行。页面文本：${t.slice(0, 200)}`);
  }

  // 1) 首页
  page = await goto("/pages/home/index");
  await page.waitFor(1500);
  await shot(page, "1-home");
  ok("首页打开");

  // 2) 商家页
  page = await goto("/pages/merchants/index");
  /*
   * **等内容出现，别只等固定时间。**
   * 这一页没有加载态：数据没回来时它是**一片空白**（不是骨架、也不是空态），
   * 所以「截到空白」既可能是没数据，也可能只是截早了 —— 上一轮就误判成前者。
   */
  const merchants = await waitForText(page, storeName, 15000);
  await shot(page, "2-merchants");
  if (merchants.includes(storeName)) {
    ok(`商家列表看得到「${storeName}」`);
  } else {
    fail(`商家列表里没有「${storeName}」。实际文本：${merchants.slice(0, 300)}`);
  }

  // 3) 进店 —— 点名字所在的那一行
  const el = await page.$(`view:has-text("${storeName}")`).catch(() => null);
  if (el) {
    await el.tap();
    await page.waitFor(2000);
    const detail = await textOf(await miniProgram.currentPage());
    await shot(page, "3-store");
    if (!goodsName) {
      ok("已进店（未指定商品名，跳过商品断言）");
    } else if (detail.includes(goodsName)) {
      ok(`店内看得到商品「${goodsName}」`);
    } else {
      fail(`店内没有「${goodsName}」。实际文本：${detail.slice(0, 300)}`);
    }
  } else {
    fail("点不进店：列表里找不到可点的行");
  }
} catch (e) {
  const raw = String(e?.message ?? e);
  /*
   * 这个报错**每个第一次跑的人都会撞上**，而它的原文只有一句英文
   * "please make sure http port is open"，既没说在哪开、也没说还要先登录。
   * 把两步写清楚，比让下一个人再查一遍文档便宜。
   */
  if (/http port is open|ECONNREFUSED/i.test(raw)) {
    fail(
      "连不上开发者工具。要在**界面里**做两件事（命令行改不了）：\n" +
        "     1. 用微信扫码登录开发者工具\n" +
        "     2. 设置 → 安全设置 → 服务端口（CLI/HTTP 调用）→ 打开\n" +
        "     两件都做完再跑本脚本。",
    );
  } else {
    fail(`跑挂了：${raw}`);
  }
} finally {
  // **disconnect 不是 close**：close 会把 IDE 里的项目窗口一起关掉，
  // 下一次跑就得重新 `cli auto`，而报错会伪装成「连不上」
  // 这版 automator 的实例上没有 disconnect，只有 close；两个都试一下
  if (miniProgram) {
    await (miniProgram.disconnect?.() ?? Promise.resolve()).catch(() => {});
  }
  console.log("\n[e2e] 结果");
  console.log(steps.join("\n"));
  console.log(`[e2e] 截图在 ${OUT}`);
}
