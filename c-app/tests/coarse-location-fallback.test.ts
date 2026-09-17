// 位置不明时看什么（TDD-C端位置选择-地址取代自提点 §M5，判据 8/9）。
//
// 改造前：没绑社区 → 端上不带任何筛选条件去要商品 → 拿回**全平台**的货。
// 那不是「兜底」，是过滤被跳过的副作用，而它在界面上与「你这儿能买到的」
// 长得一模一样：用户加进购物车、下单，到确认页才发现送不到。
//
// 现在按精度依次降级：聚落 → 区县 → 都没有才空态要位置。
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { mockApi } from "../src/api/mocks";

/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const homePage = code("src/pages/home/index.vue");
const locationStore = code("src/stores/location.ts");

/** 演示数据全挂在西湖区 */
const HANGZHOU_XIHU = "330106";
/** 种子里一个社区都没有的区（深圳）—— 「按区筛出来是空」的那一格 */
const NOWHERE = "440300";

describe("粗定位兜底：按区看货", () => {
  it("★★★ 判据 8：按区筛真的在筛 —— 没有社区的区是空的，不是全平台", async () => {
    /*
     * **对照量先验非零**：本区能筛出货。少了这一句，下面那条在
     * 「种子里本来就一件货都没有」时也会绿，而那种绿什么也没证明。
     */
    const here = await mockApi.goodsList({ page: 1, size: 50, regionCode: HANGZHOU_XIHU });
    expect(here.records.length).toBeGreaterThan(0);

    const elsewhere = await mockApi.goodsList({ page: 1, size: 50, regionCode: NOWHERE });
    expect(elsewhere.records, "别处的区筛出了货 = 按区筛没生效，拿回的仍是全平台")
      .toHaveLength(0);
  });

  it("★★ 精确定位压过粗的：两个都传时按 communityNo 筛", async () => {
    const byBoth = await mockApi.goodsList({
      page: 1, size: 50, communityNo: "CM001", regionCode: NOWHERE,
    });
    expect(byBoth.records.length, "粗的那个覆盖了精确的结论").toBeGreaterThan(0);
  });

  it("★★★ 模糊坐标：仍不给聚落（那是噪音），但要给出所在区县", async () => {
    const ctx = await mockApi.resolveLocation(30280000, 120100000, true);
    expect(ctx.innermostNo, "模糊坐标给了聚落 = 把 5 公里误差伪装成了精确匹配").toBeNull();
    expect(ctx.regionCode, "区县也不给的话，端上除了「全平台」无处可去").toBe(HANGZHOU_XIHU);
    expect(ctx.regionName, "顶栏要说明白按哪个区在看，只给一串码等于没说").toBeTruthy();
  });

  it("★★ 判据 9：连坐标都没有 → 区县为 null，端上据此走空态", async () => {
    const ctx = await mockApi.resolveLocation(null, null, true);
    expect(ctx.regionCode, "没坐标却编出一个区 = 把一屏别处的货说成「你这儿的」").toBeNull();
  });

  it("★★★ 首页不再无条件地要商品 —— 没聚落时带上区", () => {
    expect(homePage).toContain("ensureCoarseRegion");
    expect(homePage).toContain("api.goodsList({ size: 20, communityNo, regionCode })");
    // 推荐位走同一条规矩：漏掉它的话首页上半屏仍是全平台的货
    expect(homePage).toContain("api.promotedGoods({ communityNo, regionCode })");
  });

  it("★★★ 连区都没有才空屏，且那一屏要位置 —— 不是一句「还没有商品」", () => {
    /*
     * 两件事共用一句「还没有商品」，会让定位被拒的人以为平台上什么都没有，
     * 然后离开 —— 而他只差点一下选个地址。
     */
    expect(homePage).toContain("noPlace");
    expect(homePage).toContain("home.noPlaceAction");
    expect(homePage).toContain('v-else-if="!goods.length"');
  });

  it("★★★ M6：没落进围栏时绑最近的聚落 —— 冷启动期那是常态，不是异常", () => {
    // 全市只有一两个聚落时，「不在围栏里」的人才是多数；此前他们只能按区筛，而那个区往往是空的
    expect(locationStore).toContain("ctx.innermostNo ?? ctx.nearestNo");
    expect(locationStore).toContain("api.communityDetail");
  });

  it("★★★ M6：绑最近聚落时顶栏要说距离，落进围栏时不说", () => {
    /*
     * 不说距离的话，二十公里外那家店在顶栏上与楼下那家没有任何区别 ——
     * 而这正是 M5 当初拒绝猜聚落的理由（「噪音与真结果长得一模一样」）。
     */
    expect(homePage).toContain("home.nearestPlaceHint");
    expect(homePage).toContain("location.nearestDistanceM > 0");
    // 精确那一支必须把距离清成 0，否则「落进围栏」也会被说成「最近的」
    expect(locationStore).toContain("ctx.innermostNo ? 0 :");
  });

  it("★★★ 归属要在 ensureCoarseRegion **之后**再读 —— 它会在 await 期间把聚落绑上", () => {
    /*
     * 在 await 之前读一次就不管，会让「围栏外绑最近聚落」的人看到三块互相矛盾的东西：
     * 顶栏写着「最近的取货点 · 约 19 公里」、社区名也对，而商品区是「还不知道你在哪儿」。
     * 一条错误都没有，单测与源码守卫也看不出来 —— 是小程序运行时截图抓到的。
     */
    const body = homePage.slice(homePage.indexOf("async function load()"));
    const awaitAt = body.indexOf("await location.ensureCoarseRegion()");
    const readAt = body.indexOf("const communityNo = community.community?.communityNo");
    expect(awaitAt).toBeGreaterThan(-1);
    expect(readAt).toBeGreaterThan(awaitAt);
  });

  it("★★ 「拒了」与「只给了个大概」要分得开", () => {
    // getLocation 把两者都抹成 null，于是「被拒」与「拿到了模糊坐标」变成同一件事
    expect(locationStore).toContain("getLocationDetailed");
    expect(locationStore).toContain("r.fuzzy === true");
  });
});
