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

  it("★★ 两处入口接到同一个 relocate 上 —— 顶栏那颗刻意不放", () => {
    /*
     * **首页顶栏不放「重新定位」**：点那一行本来就跳收货地址页，
     * 而那一页上当前位置那一行带着重新定位 —— 两颗按钮做同一件事，
     * 而顶栏那一行本来就只有那么宽，多一颗会把地名挤出去。
     *
     * 反向也钉住：顶栏再长出一颗就变红，否则「去掉」这个决定会被下一次
     * 顺手加回来，而没有人记得当初为什么去掉。
     */
    expect(addressPage, "收货地址页没有重新定位").toContain("location.relocate(");
    expect(pickPage, "选择地点页没有重新定位").toContain("location.relocate(");
    expect(home, "顶栏又长出一颗重新定位了 —— 与收货地址页那颗是同一件事")
      .not.toContain("location.relocate(");
  });


  // ---------------------------------------------------------------- 批次 C：共用件

  it("★★★ 一条地址长什么样**只有一份实现** —— 两屏不能各画一遍", () => {
    /*
     * 此前下单页是「姓名电话一行 + 地址一行」，收货地址页是「列表行 + 五个动作」。
     * 同一条地址在两屏上的样子不一样时，用户要重新认一遍哪个是姓名、哪个是门牌 ——
     * 而这种不一致没有任何闸门拦得住，它每一处单独看都很合理。
     *
     * 判据分两半（**只判前一半的话，页面里再画一遍它也绿**）：
     *   · 两处都引用了共用件；
     *   · 两处都不再自己拼那几个字段。
     */
    expect(addressPage, "收货地址页没用共用件").toContain("biz-address-card");
    expect(confirm, "下单页没用共用件").toContain("biz-address-card");
    for (const [name, src] of [["收货地址页", addressPage], ["下单页", confirm]] as const) {
      expect(src, `${name}还在自己拼地址那一行 —— 那就是第二份实现`)
        .not.toMatch(/\{\{ *address\.region *\}\}|a\.region \}\} \{\{ a\.detail/);
    }
  });

  it("★★★ 收货地址页上**实心按钮只有一个**", () => {
    /*
     * 早先空态给的是整块卡 + 整条实心「存为收货地址」，底部还有一条同样大的
     * 「新增地址」—— 一屏两个同等份量的主按钮，而它们不是同一个量级的动作：
     * 这一页的主动作是「挑一条地址去下单」，存不存当前位置是顺手的事。
     *
     * `sh-btn` 不带 --soft/--ghost 修饰就是实心。数它。
     *
     * **只数弹层外面那一屏。** 编辑弹层里的「保存」是另一屏的主动作，
     * 它本来就该是实心 —— 把整个文件一起数会把一条正确的设计算成违规
     * （第一版就是这样，报了 2 颗）。
     */
    const listScreen = addressPage.slice(0, addressPage.indexOf("<sh-sheet"));
    expect(listScreen.length, "找不到弹层的分界，这条守卫量的范围不对了").toBeGreaterThan(0);
    const solid = (listScreen.match(/class="sh-btn(?![a-z-])(?![^"]*--)/g) ?? []).length;
    expect(solid, `收货地址页的列表那一屏有 ${solid} 颗实心按钮 —— 只该有底部那一颗`).toBe(1);
  });


  // ---------------------------------------------------------------- 批次 E：城市切换

  it("★★★ 选了城市就**不再围着坐标搜** —— 否则换城市等于没换", () => {
    /*
     * 此前搜索只围着当前定位搜：人在深圳给北京的家填地址，搜「望京」什么也搜不到 ——
     * 而那不是一条报错，是一个空列表，他会以为那个地方不存在。
     *
     * 判据分两半（**只判「传了 city」的话，同时还传着坐标也绿**，
     * 而后端有坐标时是围着坐标搜的，于是换城市毫无效果）：
     *   · 选了城市时坐标要让位；
     *   · city 要真的传出去。
     */
    const body = bodyOf(pickPage, "async function runSearch(");
    expect(body, "选择地点页没有 runSearch 了").not.toBeNull();
    expect(body, "选了城市还照样传坐标 = 换城市没有任何效果")
      .toMatch(/city\.value \|\| !at\.value \? undefined/);
    expect(body, "city 没传出去").toContain("city.value?.name");
  });

  it("★★ 换完城市要重搜 —— 不重搜他看到的是上一个城市的结果", () => {
    /*
     * 而那份列表里没有任何地方写着它是哪个城市的。
     *
     * **这里不用 bodyOf**：它解析不了回调式调用 —— `onShow(() => {…})` 的
     * 右括号在整段的最后，于是它取到的是下一个函数的体。
     * 直接量「取信箱之后近处有没有重搜」。
     */
    expect(pickPage, "选择地点页不收城市选择的结果").toContain("pickedCity.take");
    expect(pickPage, "换了城市却不重搜 —— 他看到的还是上一个城市的结果")
      .toMatch(/pickedCity\.take\(\)[\s\S]{0,240}runSearch\(/);
  });


  // ---------------------------------------------------------------- 国家/地区先收着

  it("★★★ 国家选择器关着，**而海外那条分支必须还在**", () => {
    /*
     * 入口先不露出来：露出来就是一句承诺 —— 用户选了阿联酋会以为这条地址
     * 真能寄到，而那取决于有没有商家在发那儿的货。
     *
     * **两向都钉**。只钉「关着」的话，下一个人会顺手把海外那一整段删掉，
     * 于是「将来再放开」变成「将来重写一遍」；只钉「分支还在」的话，
     * 选择器又会被顺手放出来。关着的这一半才是生产常态。
     */
    const form = code("src/components/biz/biz-address-form.vue");
    expect(form, "选择器又露出来了 —— 放开前先确认有商家在发那儿的货")
      .toContain("const SHOW_COUNTRY_PICKER = false;");
    expect(form, "选择器没挂在开关上，改常量不起作用")
      .toMatch(/v-if="SHOW_COUNTRY_PICKER"/);
    // 能力本身要留着：改一个常量就能放开，不用回头再接一遍线
    expect(form, "海外那条分支被删了").toContain('draft.value.countryCode !== "CN"');
    expect(form, "海外的表单形状没了").toMatch(/v-if="overseas"/);
  });


  // ---------------------------------------------------------------- 返回要看到刚存的那条

  it("★★★ 存完回到列表,**刚存的那条要在** —— 页面靠 onShow 重拉", () => {
    /*
     * 新建与编辑改成整页之后,存完是 navigateBack 回列表,而列表此前只在
     * onLoad 里拉过一次 —— 刚存的那条不出现,用户以为没存上,回去再存一遍。
     *
     * 弹层时代不需要这一句:那时 save() 就在列表页里,直接把接口返回的新列表
     * 赋给了 list。把表单搬出去时,那条隐含的刷新路径跟着断了 ——
     * 而页面不报错、不空白,只是少一条。
     *
     * 下单页早就是这么写的(它的注释里写着「只留在这里一处」),
     * 所以这条守卫两页一起钉:**凡是「离开去改、回来要看到结果」的页面都要有**。
     */
    for (const [name, src, fn] of [
      ["收货地址页", addressPage, "load("],
      ["下单页", confirm, "loadAddresses("],
    ] as const) {
      expect(src, `${name}没有 onShow`).toContain("onShow(");
      const body = src.slice(src.indexOf("onShow("));
      expect(body.slice(0, 400), `${name}的 onShow 里没有重拉 —— 回来看到的是旧列表`)
        .toContain(fn);
    }
  });

  it("★★ 列表只在 onShow 里拉一次 —— onLoad 里别再拉一遍", () => {
    /*
     * onShow 首次显示时也会跑。两处各拉一次 = 每次进页面都发两条一样的请求。
     *
     * **不用 bodyOf**：`onLoad((q) => {…})` 是回调式调用，它的右括号在整段最后，
     * 而 bodyOf 是「走到右括号再找第一个 `{`」—— 取到的是下一个函数的体。
     * 这个坑我在城市切换那条守卫上刚记过一次，这儿又踩了，所以两处都写明。
     */
    const at = addressPage.indexOf("onLoad((q)");
    expect(at, "收货地址页没有 onLoad").toBeGreaterThan(0);
    const body = addressPage.slice(at, addressPage.indexOf("\n});", at));
    expect(body, "onLoad 里又拉了一次").not.toMatch(/(?<!location\.)\bload\(\)/);
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
