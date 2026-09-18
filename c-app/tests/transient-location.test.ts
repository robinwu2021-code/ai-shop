// 「当前位置」是**上下文，不是资料**（PRD §6.1.0）。
//
// 这一单的四条约束全是「不许发生什么」——不入地址簿、不写服务端、不跨会话、
// 不存也能下单。这类约束最容易悄悄失效：多写一行 `api.saveAddress(...)`
// 谁也不会报错，只是用户的地址簿开始莫名其妙地长，
// 而他每次「用一下现在这儿」都在给自己攒一条永远不会再用的记录。
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

/** 取函数体：跳过参数表再按花括号配平（理由见 address-pick.test.ts 的同名函数） */
function bodyOf(src: string, signature: string): string | null {
  const at = src.indexOf(signature);
  if (at < 0) return null;
  const rest = src.slice(at);
  let paren = 0;
  let i = 0;
  for (; i < rest.length; i++) {
    if (rest[i] === "(") paren++;
    else if (rest[i] === ")") {
      paren--;
      if (paren === 0) break;
    }
  }
  const braceAt = rest.indexOf("{", i);
  if (braceAt < 0) return null;
  let depth = 0;
  for (let j = braceAt; j < rest.length; j++) {
    if (rest[j] === "{") depth++;
    else if (rest[j] === "}") {
      depth--;
      if (depth === 0) return rest.slice(braceAt, j + 1);
    }
  }
  return null;
}

const store = code("src/stores/location.ts");
const confirm = code("src/pages/order-confirm/index.vue");
const home = code("src/pages/home/index.vue");
const addressPage = code("src/pages/address/index.vue");
const pickPage = code("src/pages/address-pick/index.vue");

describe("当前位置：一次性上下文", () => {
  it("★★★ useTransient **不写地址簿、不写服务端**", () => {
    const body = bodyOf(store, "async useTransient(");
    // 取不到函数体要红：改了名之后静默返回 null，下面每一条否定断言都会变成空转
    expect(body, "useTransient 找不到了 —— 下面的断言会全部空转").not.toBeNull();
    expect(body).not.toContain("saveAddress");
    expect(body).not.toContain("switchActiveAddress");
    expect(body).not.toContain("api.addAddress");
    // 对照量：它确实在干活（绑社区池），不是一个空函数让上面几条碰巧通过
    expect(body).toContain("community.bind");
  });

  it("★★★ 顶栏要把「当前位置」标出来 —— 与「按家的地址在逛」看到的货不是一回事", () => {
    /*
     * 两种状态显示成同一个样子的话，用户会把此刻的商品当成家里能买到的，
     * 下单才发现送不到。label 也要压过生效地址，否则顶栏写着「家」而货是这儿的。
     */
    expect(store).toContain("isTransient:");
    expect(bodyOf(store, "label: (s) =>") ?? store.slice(store.indexOf("label: (s) =>"), store.indexOf("isTransient:")))
      .toContain("transientName");
    expect(home).toContain("location.isTransient");
    expect(home).toContain("home.hereTag");
  });

  it("★★★ 切回地址簿里的一条，这一次的「当前位置」要结束", () => {
    // 不清的话顶栏一直挂着「当前位置 · XX」，而货已经按新地址换过了
    const body = bodyOf(store, "async switchTo(");
    expect(body, "switchTo 找不到了").not.toBeNull();
    expect(body).toContain("this.transientAt = null");
  });

  it("★★★ 存成地址是**问出来的**，不是替他存的", () => {
    /*
     * 实现已经收进 store（此前下单页与收货地址页各写一份、走两条路）。
     * **量的是那唯一一份**：跳到新建地址页让他补姓名电话，而不是当场落一条。
     */
    const body = bodyOf(store, "gotoSaveHere(");
    expect(body, "store 里没有 gotoSaveHere").not.toBeNull();
    expect(body).toContain("navigateTo");
    expect(body, "当场落一条 = 地址簿 20 条上限很快塞满，且姓名电话都还没有")
      .not.toContain("saveAddress");
    expect(body).not.toContain("api.");
  });

  it("★★★ 「存为收货地址」**全端只有一处实现** —— 同一个字不能有两种流程", () => {
    /*
     * 此前两份两条路：收货地址页先跳选择地点页（`?useHere=1`）再交回来，
     * 下单页直接带坐标进新建预填。用户在两个地方点同一个字，得到两种流程 ——
     * 前者会多问一次地点，而地点其实已经解析好了。
     *
     * 判据是「跳转那一句只出现在 store 里」：调用点可以有很多个，
     * **实现只能有一个**。
     */
    for (const [name, src] of [["下单页", confirm], ["收货地址页", addressPage]] as const) {
      const body = bodyOf(src, "function saveHereAsAddress(");
      expect(body, `${name}没有 saveHereAsAddress`).not.toBeNull();
      expect(body, `${name}又自己拼了一次跳转 —— 两处迟早走成两条路`)
        .not.toContain("latE6=");
      expect(body, `${name}该调 store 那一份`).toContain("gotoSaveHere");
    }
  });

  it("★★★ 这一问**只在这一单真的要送**时出现 —— 自提不留地址也照样下单", () => {
    /*
     * 它必须**在 `needAddress` 那个 <view> 里面**。挪到外面的话，
     * 自提用户也会看到一句「要送到这儿吗」—— 而他这一单根本不需要地址，
     * 那句话只会让他以为不存就下不了单。
     *
     * ⚠️ 第一版我是拿下标比大小写的（在 needAddress 之后、在下一个分支之前）——
     * 消融时把整块挪到分支外面，那几个不等式**照样成立**，用例一声不吭地绿着。
     * 「看起来界定了一个区域」和「真的界定了」是两回事，得按标签配平数。
     */
    const branch = viewAt(confirm, 'v-else-if="needAddress"');
    expect(branch, "needAddress 那个分支找不到了 —— 下面的断言会空转").not.toBeNull();
    expect(branch).toContain("confirm.saveHere");
  });

  // ---------------------------------------------------------------- 批次 B：单一真源

  it("★★★ 「我在哪」只有一个取法 —— 四个页面不许各拼一份", () => {
    /*
     * 此前：首页拼归属+距离+粗定位、我的页读 label、收货地址页读归属名、
     * 选择地点页读本次 resolve。**四处迟早给出四个答案，而它们不同时
     * 界面上没有任何提示** —— 实测截图里两页并排显示的就不是同一个位置。
     *
     * 判据是「页面里不再出现自己解析的那两句」。它们都收进了 store 的 ensureHere。
     */
    for (const [name, src] of [
      ["收货地址页", addressPage],
      ["选择地点页", pickPage],
    ] as const) {
      expect(src, `${name}还在自己调 resolveLocation —— 那就是第二个真源`)
        .not.toContain("api.resolveLocation");
      expect(src, `${name}还在自己取坐标 —— 坐标与地名要一起来，否则又是两个时刻`)
        .not.toContain("getLocationDetailed(");
    }
  });

  it("★★★ ensureHere 的过期判据是**时刻**，不是「拉过没有」", () => {
    /*
     * App 的进程比一次性加载活得久：「拉过没有」那种写法在 App 上等于
     * 整段会话都用第一次的结果 —— 人走出两公里，顶栏还写着出门前那个地方。
     * H5 每次刷新都是新进程，所以这个缺陷在 H5 上永远看不见。
     */
    const body = bodyOf(store, "async ensureHere(");
    expect(body, "store 里没有 ensureHere").not.toBeNull();
    expect(body, "没有按时刻判过期 = 这一趟会话里它只会取一次")
      .toMatch(/Date\.now\(\)\s*-\s*this\.here\.at\s*<\s*HERE_TTL_MS/);
  });

  it("★★★ relocate 要把依赖这次定位的下游一起清掉", () => {
    /*
     * 留着的话，点完只有一半会变 —— 而哪一半会变取决于上一次走的是哪条分支。
     * 那种不一致没人查得出来：两次点击表现不同，却都「有反应」。
     */
    const body = bodyOf(store, "async relocate(");
    expect(body, "store 里没有 relocate").not.toBeNull();
    for (const f of ["coarseRegion", "nearestDistanceM", "communityChecked"]) {
      expect(body, `relocate 没清 ${f}`).toContain(f);
    }
    expect(body, "relocate 要强制重取，不能吃缓存").toContain("ensureHere(true)");
  });

  it("★★ 三处入口都接到同一个 relocate 上", () => {
    expect(home, "首页顶栏没有重新定位").toContain("location.relocate(");
    expect(addressPage, "收货地址页没有重新定位").toContain("location.relocate(");
    expect(pickPage, "选择地点页没有重新定位").toContain("location.relocate(");
  });

});

/**
 * 取某个 `<view ...>` 元素的完整片段（含其内部嵌套的 view）。
 * 按 `<view` / `</view>` 配平数 —— 下标比大小界定不出「在不在里面」。
 */
function viewAt(src: string, marker: string): string | null {
  const at = src.indexOf(marker);
  if (at < 0) return null;
  const open = src.lastIndexOf("<view", at);
  if (open < 0) return null;
  let depth = 0;
  const re = /<view\b|<\/view>/g;
  re.lastIndex = open;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    if (m[0] === "</view>") {
      depth--;
      if (depth === 0) return src.slice(open, m.index + m[0].length);
    } else {
      depth++;
    }
  }
  return null;
}
