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
    const body = bodyOf(confirm, "function saveHereAsAddress(");
    expect(body, "saveHereAsAddress 找不到了").not.toBeNull();
    // 跳到新建地址页让他补姓名电话，而不是当场落一条
    expect(body).toContain("navigateTo");
    expect(body).not.toContain("saveAddress");
    expect(body).not.toContain("api.");
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
