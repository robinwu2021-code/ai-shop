/**
 * 每日流水补齐与按天明细（TDD-B 端每日流水补齐与按天明细）。
 *
 * <p><b>为什么读源码而不是挂载页面</b>：b-app 没有 `@vue/test-utils`（只有 c-app 有），
 * 而在 b-app 跑 `npm i` 会打断小程序构建（本仓库已知坑）。同目录的
 * `account-page.test.ts`、`order-discount-lines.test.ts` 也是读源码断言。
 *
 * <p><b>代价说清楚</b>：源码断言只能证明「那几行字在文件里」，证不了渲染结果。
 * 所以界面本身另外用 H5 预览看过（TDD §6 记结论），这里钉的是
 * **契约接没接上、判据取的是哪个字段** —— 那些恰好是源码断言答得了的。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (p: string) => readFileSync(resolve(__dirname, p), "utf-8");

/**
 * 去掉注释。**反向断言（`not.toContain`）一律在这个之上做。**
 *
 * 本仓库踩过：注释里写着「别用 X」，于是「不应出现 X」那条恒红，
 * 而报错指向的是一处完全无关的地方。下面两条就是 ——
 * 我在注释里写了「不用 toISOString」和那个动态键的形状，
 * 第一次跑它们直接红了，而代码里一个都没有。
 */
function code(src: string) {
  return src
    .replace(/<!--[\s\S]*?-->/g, "")   // 模板注释
    .replace(/\/\*[\s\S]*?\*\//g, "") // 块注释
    .replace(/^\s*\/\/.*$/gm, "");     // 行注释（整行的；行尾的留着，它们不含判据词）
}
const income = read("../src/pages/income/index.vue");
const settle = read("../src/pages/settle/index.vue");
const http = read("../src/api/http.ts");
const mock = read("../src/api/mocks/settle.ts");
const zh = read("../src/i18n/locale/zh-CN.ts");
const en = read("../src/i18n/locale/en.ts");
const ar = read("../src/i18n/locale/ar.ts");

describe("B 端每日流水补齐与按天明细", () => {
  /*
   * 前置断言：文件真读到了。下面有一批 `not.toContain` ——
   * 读成空串的话它们会全部通过，而那正是「路径写错」最想被拦住的那一刻。
   */
  it("★★★ 七个文件都读到了内容", () => {
    for (const [name, s] of Object.entries({ income, settle, http, mock, zh, en, ar })) {
      expect(s.length, `${name} 读空了`).toBeGreaterThan(500);
    }
    expect(income).toContain("<template>");
    expect(settle).toContain("<template>");
  });

  it("★★★ AC3 每日那行说了佣金与服务费 —— 契约一直有这两列，端上此前零引用", () => {
    expect(income).toContain("income.dailyCommission");
    expect(income).toContain("income.dailyFee");
    expect(income).toContain("d.commissionMinor");
    expect(income).toContain("d.serviceFeeMinor");
  });

  it("★★ AC3 佣金与服务费只在非零时出现 —— 与退款/快递费同一条规则", () => {
    /*
     * 自带客流零佣金、非自提无服务费。天天挂两行 ¥0.00 是噪音，
     * 而噪音会让真正有扣款的那天**不显眼** —— 这一行存在的理由正是让它显眼。
     */
    expect(income).toContain('v-if="d.commissionMinor > 0"');
    expect(income).toContain('v-if="d.serviceFeeMinor > 0"');
  });

  it("★★★ AC4 每日那行可点，带 day 跳结算单页", () => {
    expect(income).toContain('@tap="openDay(d.day)"');
    expect(income).toContain("${ROUTES.settle}?day=${day}");
  });

  it("★★ AC4 可点要给得出来 —— 不靠「这一行好像能点」让人猜", () => {
    // 右边一枚箭头。纯靠手感的可点区域在真机上基本不会被按
    const i = income.indexOf('@tap="openDay(d.day)"');
    expect(i).toBeGreaterThan(-1);
    expect(income.slice(i, i + 600)).toContain('name="chevronRight"');
  });

  it("★★★ AC4 结算单页从 onLoad 读 day，并把它传给接口", () => {
    expect(settle).toContain("onLoad((q)");
    expect(settle).toContain("q?.day");
    expect(settle).toContain("api.mSettleList(allStores.value, day.value || undefined)");
    // http 层真发出去了。只改签名不改 http 是本仓库踩过的「只写不读」那一类
    expect(http).toContain("mSettleList: (allStores, day)");
    expect(http).toContain("...(day ? { day } : {})");
  });

  it("★★ AC4 参数名不许撞 sh-scaffold 的属性名", () => {
    /*
     * 撞上的话查询参数会被当成外壳配置透下去：本仓库踩过 `?tab=` ——
     * 页面长出一条空菜单、返回键消失，而页面本身看着是对的。
     */
    for (const prop of ["tab", "title", "titleKey", "titleSuffix", "padded", "denied", "failed"]) {
      expect(code(settle), `day 参数不该叫 ${prop}`).not.toContain(`q?.${prop} as string`);
    }
    expect(settle).toContain("const day = ref(");
  });

  it("★★ AC4 筛选态把无关的卡收起来", () => {
    // 商家是来核一天的账的，不是来改积分开关的
    expect(settle).toContain('v-if="!dayMode"');          // 四个入口卡
    expect(settle).toContain('v-if="rate && !dayMode"');  // 费率卡
    expect(settle).toContain('v-if="points && !dayMode"'); // 积分卡
    expect(settle).toContain("settle.dayClear");          // 出得去
  });

  it("★★ AC4 这一天的小计从筛出来的行自己加 —— 不再调一次每日流水", () => {
    /*
     * 再调一次就是第二个真源，而两处对不上的那天，
     * 商家只会理解成「平台算错了我的钱」。
     */
    expect(settle).toContain("const daySum = computed(() => bills.value.reduce(");
    expect(settle).toContain("settle.dayBills");
    expect(code(settle)).not.toContain("mDailyFlow");
  });

  it("★★★ AC6 清单行显示成交日，不是 createdAt", () => {
    expect(settle).toContain("billDay(b)");
    expect(settle).toContain("b.accruedAt == null");
    // 存量行说出来，不回落到 createdAt —— 回落的话清单与每日流水从此对不上
    expect(settle).toContain("settle.accruedNone");
    expect(code(settle)).not.toContain("monthDay(b.createdAt)");
  });

  it("★★ AC6 mock 按 accruedAt 分天与筛天，与真后端同一个判据", () => {
    /*
     * mock 此前按 `createdAt` 分天，理由写着「端上的 SettleBill 没有这一列」——
     * 那一列现在有了。差异留着的代价是「点开某一天」在 mock 下
     * 筛出来的和那张表对不上，而 mock 正是日常改界面时唯一在跑的那一套。
     */
    expect(mock).toContain("b.accruedAt != null");
    expect(code(mock)).not.toContain("if (b.createdAt < from || b.createdAt > to) continue;");
    // 存量行在 mock 下也要存在，否则 undated 那块界面永远是隐藏的
    expect(mock).toContain("earliest.accruedAt = null");
  });

  it("★★★ AC7 三枚区间胶囊，默认仍是近 30 天", () => {
    expect(income).toContain('const range = ref<Range>("last30")');
    expect(income).toContain("income.rangeLast30");
    expect(income).toContain("income.rangeThisMonth");
    expect(income).toContain("income.rangeLastMonth");
    // 区间要真的传下去，否则胶囊点了也只是变个颜色
    expect(income).toContain("...rangeQuery(range.value)");
  });

  it("★★ AC7 近 30 天返回空区间 —— 让后端用它自己那个默认", () => {
    // 端上再算一遍「30 天」的话，两处对它的理解迟早差一天
    expect(income).toContain('if (r === "last30") return {}');
    // 日期按本地切，不走那个 UTC 的转换 —— 东八区的今天会变成昨天
    expect(code(income)).not.toContain("toISOString");
  });

  it("★★ 词条键写成字面量，不靠模板串拼", () => {
    /*
     * 动态键不进 i18n 对账：这几条会被当成没人用的孤儿词条，
     * 而真正缺词条的那天页面直接露出裸 key。
     */
    expect(code(income)).not.toMatch(/\$t\(`income\.range\$\{/);
    expect(income).toContain("labelKey: \"income.rangeLast30\"");
  });

  it("★★★ 九条新词条三语齐，没有只改中文的半套", () => {
    const keys = [
      "dailyCommission", "dailyFee", "rangeLast30", "rangeThisMonth", "rangeLastMonth",
      "dayFilter", "dayBills", "dayClear", "accruedNone",
    ];
    for (const [lang, s] of Object.entries({ zh, en, ar })) {
      for (const k of keys) {
        // 值不许是空串：有键没值在界面上就是一片空白，而闸门只数键
        expect(s, `${lang} 缺 ${k}`).toMatch(new RegExp(`\\b${k}:\\s*"[^"]+"`));
      }
    }
  });
});
