import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * 首屏的身份边界：**只认人，不要东西。**
 *
 * <h2>这个文件为什么被重写</h2>
 * 上一版在测试文件里**自己重写了一份 `ensureIdentity`**，然后测那一份。
 * 于是 2026-09-03 把「首屏问手机号」整个搬走之后，它**照样全绿** ——
 * 测的是副本，页面怎么改都影响不到它。
 * 那种测试比没有测试更坏：它占着「这里有守卫」的位置。
 *
 * <h2>改成读页面源码</h2>
 * 首页组件挂载要拖进十几个 store 与网络桩，成本高且脆。而这条规则本身
 * 是**结构性**的（「首页不许出现手机号弹层」），读源码就能判，且判得准。
 * 代价是它管不了「弹层被别的方式唤起」——那种改法会同时动到下面这几个符号，
 * 所以还是会被抓到。
 */
const HOME = resolve(__dirname, "../src/pages/home/index.vue");

/**
 * 判之前先剥掉注释。
 *
 * <p>**解释规则的那句话自己也要能通过规则** —— 首页里那两段注释写着
 * 「这里原先会弹 phoneGate / 原先会调 probeNearby，现在不了」，
 * 而那正是这条守卫要找的字符串。不剥的话它把说明当成违规，
 * 逼着下一个人去删掉最该留下的那段解释。
 */
function code(file: string): string {
  return readFileSync(file, "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

describe("首屏：只认人，不要东西", () => {
  const src = code(HOME);

  it("★★★ 首页不许有手机号弹层 —— 新用户第一眼该看到商品，不是表单", () => {
    for (const sym of ["phone-gate", "PhoneGate", "phoneGate"]) {
      expect(src, `首页出现了 ${sym}：手机号要在**下单那一刻**问，见 order-confirm`)
        .not.toContain(sym);
    }
  });

  it("★★★ 首页也不许把人推去选自提点 —— 前提（静默定位）已被平台拿走", () => {
    expect(src, "probeNearby 唯一的用途就是决定要不要跳；不跳之后它只是一次白花的定位请求")
      .not.toContain("probeNearby");
    expect(src).not.toContain("maybePickCommunity");
  });

  it("★★ 但身份还是要认的：静默登录 + 每次核对 profile", () => {
    expect(src, "静默登录是「打开即登录」的全部").toContain("silentLogin");
    /*
     * loadProfile 必须是**无条件**的。只在缺失时才拉的话，账号在服务端
     * 已经没了（被删/被封/已注销）时，端上会一直显示那个并不存在的身份，
     * 而且一个需要鉴权的请求都不发 —— 连 401 都触发不了，自愈永远不启动。
     */
    expect(src).toContain("loadProfile");
  });

  it("★★ 选取货点的入口没有消失 —— 不推 ≠ 不给路", () => {
    expect(src, "顶栏那一行是用户自己去选的唯一入口").toContain("gotoCommunity");
  });
});

/**
 * 手机号搬到了下单页，那里必须真的拦住 —— 否则这次改动就是**把功能弄丢了**。
 */
describe("下单页：没手机号不许提交", () => {
  const src = code(resolve(__dirname, "../src/pages/order-confirm/index.vue"));

  it("★★★ 提交前判手机号，没有就弹绑定并**中断提交**", () => {
    expect(src).toContain("phoneGate");
    expect(src, "拦截必须发生在 submit 里，不是别处").toMatch(/if \(!user\.user\?\.phone\)/);
    // `return` 是关键：只弹不返回的话，这一单会**带着空手机号提交出去**
    expect(src).toMatch(/phoneGate\.value = true;\s*\n\s*return;/);
  });

  it("★★ 绑完自动继续提交 —— 多一次点击，人会以为刚才那下没生效", () => {
    expect(src).toMatch(/@done="\(\(phoneGate = false\), submit\(\)\)"/);
  });
});
