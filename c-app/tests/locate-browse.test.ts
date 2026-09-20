/**
 * 首页顶栏点位置 → 选点页的**浏览模式**（原型 c-locate l01–l06）。TDD-C端首页位置选择。
 *
 * 守的是这一页两个模式**选完干什么**的差别：
 * 浏览模式当场切「现在按哪儿看货」，默认模式把地点交回建地址那条流程。
 * 两者在界面上长得几乎一样（同一页、同样的搜索与附近），
 * 走错了不会报错 —— 只会让顶栏说的和看到的货不是一回事。
 */
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

/**
 * 取一个函数体，按花括号配平。**取不到返回 null** ——
 * 函数改了名之后静默返回空串的话，下面每一条断言都会「通过」，
 * 而被守的那件事一行都没被检查到。
 */
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
  let depth = 0;
  let started = false;
  for (; i < rest.length; i++) {
    const ch = rest[i];
    if (ch === "{") {
      depth++;
      started = true;
    } else if (ch === "}") {
      depth--;
      if (started && depth === 0) return rest.slice(0, i + 1);
    }
  }
  return rest;
}

const pick = code("src/pages/address-pick/index.vue");
const home = code("src/pages/home/index.vue");

describe("首页顶栏进的是「选择位置」", () => {
  it("★★★ gotoPlace 去选点页的浏览模式，不去收货地址页", () => {
    const body = bodyOf(home, "function gotoPlace(");
    expect(body, "首页没有 gotoPlace 了 —— 守卫失去了扫描对象，先修守卫").not.toBeNull();
    expect(body, "顶栏问的是「按哪儿看货」，收货地址页回答的是「寄到哪」").toContain("mode=browse");
    expect(body).toContain("ROUTES.addressPick");
  });
});

describe("浏览模式选完就切，不写地址簿", () => {
  it("★★★ choose 在浏览模式走 useTransient 并返回，不 offer 给建地址流程", () => {
    const body = bodyOf(pick, "async function choose(");
    expect(body, "choose 改名了或不再是 async —— 先修守卫").not.toBeNull();
    const browseBranch = body!.slice(body!.indexOf("if (browse"), body!.indexOf("pickedPlace.offer"));
    expect(browseBranch, "浏览模式必须当场切浏览位置").toContain("useTransient");
    expect(browseBranch, "切浏览位置不该写地址簿（上限 20 条，逛一次存一条很快就满）")
      .not.toContain("pickedPlace.offer");
  });

  it("★★★ 点一条收货地址走 switchTo，不是当成一个「地点」交回去", () => {
    const body = bodyOf(pick, "async function pickAddress(");
    expect(body, "浏览模式里没有 pickAddress —— 我的收货地址那一段点了没反应").not.toBeNull();
    expect(body).toContain("switchTo");
    expect(body, "存过的地址是长期偏好，不该退化成一次性的临时位置").not.toContain("useTransient");
  });

  it("浏览模式底下那颗是出口：去新建地址，不拦人", () => {
    const body = bodyOf(pick, "function addAddress(");
    expect(body, "没有新增地址的出口").not.toBeNull();
    expect(body).toContain("ROUTES.addressEdit");
  });
});

describe("两段空态各自消失", () => {
  const tpl = readFileSync(
    resolve(__dirname, "..", "src/pages/address-pick/index.vue"),
    "utf-8",
  ).replace(/<!--[\s\S]*?-->/g, "");

  it("★★★ 没有地址时不渲染「我的收货地址」整段", () => {
    expect(tpl, "一条地址都没有时留一个空标题，等于承诺了内容又不给")
      .toContain('v-if="browse && location.list.length"');
  });

  it("★★★ 这一段只在浏览模式出现", () => {
    // 默认模式这一页在「造一条地址」，摆出地址簿等于让人在建地址时去挑一条已有的
    expect(tpl).toContain("addressPick.myAddresses");
    expect(tpl.slice(tpl.indexOf("addressPick.myAddresses") - 400, tpl.indexOf("addressPick.myAddresses")))
      .toContain("browse");
  });

  it("没有坐标时「附近」整段本来就不渲染（既有行为，一并钉住）", () => {
    expect(tpl).toContain('v-if="nearbyPickable.length || failed"');
  });
});

describe("从新增地址返回，刚存的那条要出现", () => {
  it("★★★ 浏览模式在 onShow 里重拉地址簿", () => {
    // 只在 onLoad 拉：真机上建完地址返回，「我的收货地址」整块还是不显示（2026-09-20）
    const onShow = bodyOf(pick, "onShow(");
    expect(onShow, "没有 onShow 了 —— 守卫失去了扫描对象").not.toBeNull();
    expect(onShow).toContain("location.load()");
  });
});

describe("标题分得开", () => {
  it("浏览模式的标题不是「选择收货地址」", () => {
    expect(pick).toContain("addressPick.browseTitle");
  });
});
