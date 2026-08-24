/**
 * 真实链路验「打开即注册/登录」。
 *
 * <p>做法是**两遍**：
 * <ol>
 *   <li>清空存储 → 重启小程序 → 应当**无需任何点击**就拿到会话</li>
 *   <li>再清一次存储 → 再重启 → 还是拿到会话，但**不该再多一个账号** ——
 *       同一个微信第二次进来是「认出他」，不是「再注册一个」</li>
 * </ol>
 *
 * <p>第二遍才是这条链路真正的价值。只跑第一遍的话，一个「每次打开都建新号」的实现
 * 也会绿 —— 而那意味着每个用户的订单、卡券、积分每次打开都清零，
 * 且不报错、不空白，只是他的东西不见了。
 *
 * <p>账号数从**生产库**读（ssh + mysql），不是从端上读：
 * 端上只看得到「我是谁」，看不到「系统里多了没多一个人」。
 */
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";

const nodeRequire = createRequire(import.meta.url);
nodeRequire("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};
const automator = nodeRequire("miniprogram-automator");
const { spawn } = await import("node:child_process");

const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PROJECT = "/Users/robin/work/ai/ai-shop/c-app/dist/build/mp-weixin";
const PORT = 9420;
/** 与 shared/utils/constants 的 STORAGE 对齐：shc(VITE_APP_NS) + r(真后端) */
const TOKEN_KEY = "shcr_token";

const steps = [];
const ok = (m) => (steps.push(`  ✓ ${m}`), console.log(`  ✓ ${m}`));
const bad = (m) => (steps.push(`  ✗ ${m}`), console.error(`  ✗ ${m}`), (process.exitCode = 1));

/** 生产库里的消费者账号总数 —— 判断「新建」还是「认出」的唯一可靠依据 */
function accountCount() {
  const out = execFileSync("ssh", [
    "soukmind-tx",
    'sudo mysql -N -e "use ai_shop; select count(*) from usr_account;" 2>/dev/null',
  ]);
  return Number(String(out).trim());
}

function enableAutomation() {
  return new Promise((res, rej) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.stderr.on("data", (d) => (out += d));
    p.on("close", (c) => (c === 0 ? res(out) : rej(new Error(out.slice(-300)))));
  });
}

async function connect() {
  for (let i = 0; i < 15; i++) {
    try {
      return await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
    } catch {
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  throw new Error(`连不上 ws://127.0.0.1:${PORT}`);
}

/** 清空存储 → 重启小程序 → 回到「全新用户第一次打开」的状态 */
async function freshLaunch(mp) {
  await mp.evaluate(() => wx.clearStorageSync());
  /*
   * **必须让 App 的 onLaunch 重跑一遍。**
   *
   * 静默登录挂在 onLaunch 上，而 `reLaunch()` 只换页面、不重跑它 ——
   * 清了存储再 reLaunch，测到的是「没有会话，也没人去建」，那不是真实时序。
   * 上一版用 `callWxMethod("restartMiniProgram")`，那个 API **根本不存在**，
   * 被 catch 吞掉之后整轮测的都是没重启过的旧进程：两遍都报「没有会话」，
   * 而生产日志里连一条登录请求都没有 —— 那才是真相。
   *
   * 真正能重启的只有「关掉项目再让 cli auto 拉起来」。
   */
  await mp.close().catch(() => {});
  await new Promise((r) => setTimeout(r, 2000));
  await enableAutomation();
  const fresh = await connect();
  await new Promise((r) => setTimeout(r, 5000));
  return fresh;
}

let mp;
try {
  await enableAutomation();
  mp = await connect();

  // ---- 第一遍：全新状态
  const before1 = accountCount();
  mp = await freshLaunch(mp);
  const token1 = await mp.evaluate((k) => wx.getStorageSync(k), TOKEN_KEY);
  const after1 = accountCount();

  if (token1) {
    ok(`第一遍：无需点击已拿到会话（token 长度 ${String(token1).length}）`);
  } else {
    bad("第一遍：打开之后没有会话 —— 静默登录没生效");
  }
  console.log(`  · 账号数 ${before1} → ${after1}`);

  // ---- 第二遍：同一个微信，再来一次
  const before2 = accountCount();
  mp = await freshLaunch(mp);
  const token2 = await mp.evaluate((k) => wx.getStorageSync(k), TOKEN_KEY);
  const after2 = accountCount();

  if (token2) {
    ok("第二遍：同样无需点击就拿到会话");
  } else {
    bad("第二遍：没有会话");
  }

  if (after2 === before2) {
    ok("第二遍**没有多出账号** —— 同一个微信被认出来了，不是又注册一个");
  } else {
    bad(
      `第二遍又建了 ${after2 - before2} 个账号 —— 每次打开都建新号，` +
        "意味着用户的订单/卡券/积分每次打开都清零，而且不报错",
    );
  }
} catch (e) {
  bad(`跑挂了：${String(e?.message ?? e).slice(0, 200)}`);
} finally {
  // 这版 automator 的实例上没有 disconnect，只有 close
  await (mp?.disconnect?.() ?? Promise.resolve()).catch(() => {});
  console.log("\n[e2e] 结果");
  console.log(steps.join("\n"));
}
