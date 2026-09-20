// 「当前生效位置」：用户存了多个位置（家 / 公司 / …），任一时刻只按一个看货。
//
// **它不是收货地址。** 同一条记录常常两者都是，但回答的是不同问题：
//   生效位置 = 现在按哪儿看货（浏览上下文）
//   默认地址 = 下单预填哪个收货人（长期偏好）
// 给父母下单时两者不一样。合成一个的后果是改了一个另一个跟着变。
import { defineStore } from "pinia";
import { api } from "@/api";
import { ApiError } from "@shared/net/http-client";
import { getLocationDetailed } from "@shared/ports/location";
import { useCommunityStore } from "./community";
import type { Address, ResolvedPlace } from "@shared/types";
import { metersBetweenE6 } from "@shared/utils/geo";

/** 定位要多近才算「匹配到了这条地址」。再远就是「附近碰巧存过一个地址」 */
const MATCH_NEAR_M = 1000;

/**
 * 「我在哪」多久重取一次。
 *
 * <p>五分钟：短到走出几条街会跟上，长到反复进出首页不会一直弹定位。
 * **判据是时刻不是次数** —— 见 {@code ensureHere}。
 */
const HERE_TTL_MS = 5 * 60 * 1000;

export const useLocationStore = defineStore("location", {
  state: () => ({
    /** 当前生效位置。**null 是常态**，不是错误：新用户一个都没有 */
    active: null as Address | null,
    /**
     * **「我在哪」的唯一真源。**
     *
     * <p>此前四个页面各拼一份：首页拼归属+距离+粗定位、我的页读 `label`、
     * 收货地址页读归属名、选择地点页读本次 resolve —— 四处迟早给出四个答案，
     * 而它们不同时界面上没有任何提示。实测截图里就出现过：
     * 收货地址页写着「桂澜新村」（几天前绑的归属），
     * 选择地点页写着「使用当前位置」（这一次的定位，还没回来）。
     *
     * <p>`at` 是取到的**时刻**，过期判据用它。
     * **不能写成「拉过没有」** —— App 的进程比一次性加载活得久，
     * 那种写法在 App 上等于整段会话都用第一次的结果。
     */
    here: null as {
      coords: { lat: number; lng: number };
      coarse: boolean;
      place: ResolvedPlace | null;
      /** 所在区县名。模糊定位时顶栏只说到这一级（见 label） */
      regionName: string | null;
      at: number;
    } | null,
    /**
     * 这次会话里用户**显式挑过**一条收货地址来逛。
     *
     * <p>M9 定了顶栏跟当前定位（快递能送到任何地方，顶栏回答的是
     * 「我在看哪一带的货」）。但用户自己点过某条地址就该听他的 ——
     * 那是一个明确的动作，被自动定位悄悄顶掉比不跟定位更糟。
     *
     * <p><b>刻意不持久化</b>：下次打开又回到「跟定位」。
     */
    pickedByUser: false,
    /**
     * 「当前位置」这一次逛的坐标。**不入地址簿、不写服务端** ——
     * 它是上下文不是资料（PRD §6.1.0）。App 重开就没了，那正是「现在这儿」的定义。
     */
    transientAt: null as { lat: number; lng: number } | null,
    /**
     * 「当前位置」解析出来的地名。**顶栏必须把它说出来** ——
     * 不说的话这一屏与「按家的地址在逛」长得一模一样，
     * 而他看到的货是按现在这儿算的：两种状态显示成同一个样子，
     * 用户会把此刻的商品当成家里能买到的，下单才发现送不到。
     */
    transientName: "",
    list: [] as Address[],
    loading: false,
    /**
     * 粗定位落到的**区县**。「位置不明」与「看全平台的货」之间的那一格。
     *
     * <p>此前没有这一格：解析不出聚落，端上就不带任何筛选条件去要商品，
     * 拿回来的是全平台的货 —— 那不是一个决定，是过滤被跳过的副作用。
     * 而它在界面上与「这就是你这儿的货」长得一模一样，用户下单才发现送不到。
     */
    coarseRegion: null as { code: string; name: string } | null,
    /**
     * 这次会话有没有核过「存着的那个聚落还在不在」。
     * 只核一次：核的是一条几乎不会变的事实，每次进首页都问一遍是白花的往返。
     */
    communityChecked: false,
    /**
     * 定位到底拿没拿到。`null` = 这次会话还没探过。
     * **false 才是那唯一该空屏的一格**：连模糊定位都被拒，此时要位置，不是列一屏买不到的东西。
     */
    located: null as boolean | null,
    /**
     * 默认归属离他有多远（米）。**绑的是「最近的聚落」时必须说出来**（M6）。
     *
     * <p>这不是文案，是这一级成不成立的前提。M5 当初拒绝按模糊坐标猜聚落，
     * 理由是「噪音在界面上与真结果长得一模一样」；M6 开始猜了，
     * 就必须让它**长得不一样** —— 不说距离的话，二十公里外的店在顶栏上
     * 与楼下那家一模一样，而货要从二十公里外送过来。
     *
     * <p>`0` = 不是这一级（落进围栏了，或者按区看）。
     */
    nearestDistanceM: 0,
  }),

  getters: {
    /**
     * 顶栏显示的短名：优先用标签（家/公司），否则用详细地址。
     *
     * <p><b>「当前位置」压过生效地址</b>：他刚点了「用现在这儿」，
     * 此刻看到的货就是按那个点算的 —— 顶栏还显示「家」就是在说假话。
     */
    label: (s) => {
      // 用户显式挑过地址就听他的（见 pickedByUser）
      if (s.pickedByUser && s.active) {
        return s.active.tag || s.active.detail || s.active.region || "";
      }
      /*
       * **模糊定位只说到区。**（2026-09-19 真机：人在龙华体育馆，顶栏写「棱镜·男生公寓(清湖地铁站总店)」）
       *
       * 走到 getFuzzyLocation 的那一次（精确定位被拒 / 接口不可用）——
       * 坐标故意偏几公里、只准到区。拿这个点去反查地名，得到的是偏移点旁边的某个楼盘，
       * 用一个看着很精确的名字说一个只准到区的位置，就是在说假话。
       * 区名是真话；要准的，点顶栏去地址页在地图上选（chooseLocation 不受那条限制）。
       */
      if (s.here?.coarse) {
        const r = s.here.regionName || s.coarseRegion?.name;
        if (r) return r;
      }
      // M9：顶栏 = 当前定位。它回答的是「我在看哪一带的货」，不是「送到哪」
      if (s.here?.place?.name && !s.here.coarse) return s.here.place.name;
      if (s.transientAt && s.transientName) return s.transientName;
      if (s.active) return s.active.tag || s.active.detail || s.active.region || "";
      // 退到粗定位的区名 —— 顶栏那一行任何时候都要有内容，
      // 而「西湖区」至少是句真话：这一屏的货正是按那个区筛出来的
      return s.coarseRegion?.name ?? "";
    },
    /**
     * 「我在哪」的地名，给收货地址页、选择地点页那张「当前位置」卡。
     * 模糊定位只给区名 —— 理由同 label：偏移点反查出的楼盘名是假的精确。
     */
    hereName: (s) => (s.here?.coarse ? s.here.regionName ?? "" : s.here?.place?.name ?? ""),
    /** 顶栏写的只是个大概（模糊定位，只准到区）—— 界面上要标出来，别让人当成精确位置 */
    approx: (s) => !(s.pickedByUser && s.active) && !!s.here?.coarse
      && !!(s.here.regionName || s.coarseRegion?.name),
    /** 顶栏那个地名可能不是最新的（地图挂了、用的是库里旧的那条） */
    placeStale: (s) => s.here?.place?.stale === true,
    /** 这一次逛的是不是「当前位置」（而不是地址簿里的某一条） */
    isTransient: (s) => !!s.transientAt,
    has: (s) => !!s.active,
  },

  actions: {
    /**
     * 没有地址时，**靠定位把「看哪儿的货」定下来**。
     *
     * <p>按精度依次降级（M5 + M6）：
     * <ol>
     *   <li>落进某个围栏 → 就是它（精确）</li>
     *   <li>没落进，但最近的已开通聚落在上限内 → <b>绑它</b>，并记下距离让顶栏说出来</li>
     *   <li>够不着 → 退到按区筛（M5）</li>
     *   <li>连坐标都没有 → null，调用方走空态要位置</li>
     * </ol>
     *
     * <p>第 2 级是 M6 加的，而它正是冷启动期的常态：全市只有一两个聚落，
     * 「不在围栏里」不是异常；此前那时只能按区筛，而那个区往往一个聚落都没有 ——
     * 首页就空着，且没有任何线索说明为什么。
     *
     * <p>**结果缓存到会话结束**：这条挂在首页加载上，不缓存就是每次回首页都定位一次
     * （而定位会弹授权框）。只有还没绑定位置的用户会走到这里，绑上之后这一格就不再参与。
     *
     * @returns 区县码与名字；**绑上了聚落时返回 null** —— 那时调用方该按 communityNo 取货，
     *          再带上 regionCode 只会让后端有两个主语（精确的那个本来就压过粗的）
     */
    /**
     * **取一次「我在哪」。这是全端唯一的入口。**
     *
     * <p>过期判据是**时刻**不是「拉过没有」：App 的进程比一次性加载活得久，
     * 「拉过没有」那种写法在 App 上等于整段会话都用第一次的结果 ——
     * 人走出两公里，顶栏还写着出门前那个地方。
     *
     * @param force 用户点了「重新定位」。**强制重取，连同下游一起清** ——
     *              不清的话他点完看到同一个名字，会以为按钮坏了
     */
    async ensureHere(force = false) {
      if (!force && this.here && Date.now() - this.here.at < HERE_TTL_MS) {
        return this.here;
      }
      const r = await getLocationDetailed().catch(() => null);
      if (!r?.ok) {
        this.located = false;
        return null;
      }
      this.located = true;
      const ctx = await api
        .resolveLocation(Math.round(r.coords.lat * 1e6), Math.round(r.coords.lng * 1e6),
          r.fuzzy === true)
        .catch(() => null);
      this.here = {
        coords: { lat: r.coords.lat, lng: r.coords.lng },
        coarse: r.fuzzy === true,
        // 拿不到就留 null，界面退回区名。**不编地名**
        place: ctx?.place ?? null,
        regionName: ctx?.regionName ?? null,
        at: Date.now(),
      };
      return this.here;
    },

    /**
     * 「重新定位」。**三处入口共用这一个** ——
     * 首页顶栏、收货地址页的当前位置行、选择地点页。
     *
     * <p>把依赖这一次定位的东西一起清掉：粗定位落的区、最近归属的距离、
     * 「这次会话核过归属没有」。留着的话点完只有一半会变，
     * 而哪一半会变取决于上一次走的是哪条分支 —— 那种不一致没人查得出来。
     */
    /**
     * 「把当前位置存成收货地址」。**全端唯一的一份。**
     *
     * <p>此前有两份、两条路：收货地址页先跳选择地点页（`?useHere=1`）再交回来，
     * 下单页直接带坐标进新建预填。同一个字，两种流程 —— 前者会多问一次地点，
     * 而地点其实已经解析好了。统一走后者。
     *
     * <p><b>不自动存</b>：地址簿上限 20 条，每次「用一下现在这儿」都存一条会很快塞满；
     * 而且送到这儿要姓名电话门牌，那些他还没填。带着坐标跳去新建，让他补完 ——
     * 存下来的那条才是一条**能送到的**地址。
     *
     * @returns 跳没跳。false = 连坐标都还没有，调用方自己决定说什么
     */
    gotoSaveHere(routes: { address: string }): boolean {
      const at = this.here?.coords ?? this.transientAt;
      if (!at) return false;
      const name = this.here?.place?.name || this.transientName;
      uni.navigateTo({
        url: `${routes.address}?new=1&latE6=${Math.round(at.lat * 1e6)}`
          + `&lngE6=${Math.round(at.lng * 1e6)}&region=${encodeURIComponent(name)}`,
      });
      return true;
    },

    async relocate() {
      this.coarseRegion = null;
      this.nearestDistanceM = 0;
      this.communityChecked = false;
      this.pickedByUser = false;
      return this.ensureHere(true);
    },

    async ensureCoarseRegion(): Promise<{ code: string; name: string } | null> {
      const community = useCommunityStore();
      if (community.community) {
        /*
         * **先确认那个聚落还在。**
         *
         * 归属是持久化的，而这里又是「有归属就早退」—— 两条加起来的后果是
         * 升级前绑过的人**永远重新匹配不了**，哪怕他绑的那个聚落早已不存在。
         * 真机实况：顶栏顶着一个杭州演示数据里的便利店名，而库里根本没有那条记录。
         *
         * **只在服务端明确答「没有这条」时才清**（`ApiError` = 后端答了话）。
         * 网络不通那次不能清 —— 那会把一次地铁里的抖动变成「你的位置没了」。
         *
         * 一次会话只核一次：核的是一条几乎不会变的事实。
         */
        if (!this.communityChecked) {
          this.communityChecked = true;
          try {
            await api.communityDetail(community.community.communityNo);
          } catch (e) {
            if (e instanceof ApiError) community.clear();
          }
        }
        if (community.community) return null;
      }
      if (this.coarseRegion) return this.coarseRegion;
      // 走 Detailed 那一份：这里要分清「拒了」与「只给了个大概」，而 getLocation 把两者都抹成 null
      const r = await getLocationDetailed().catch(() => null);
      if (!r?.ok) {
        this.located = false;
        return null;
      }
      this.located = true;
      const ctx = await api
        .resolveLocation(Math.round(r.coords.lat * 1e6), Math.round(r.coords.lng * 1e6),
          r.fuzzy === true)
        .catch(() => null);
      if (!ctx) return null;

      /*
       * **落进围栏、或者有个够得着的最近聚落 —— 两种都绑。**
       * 绑不上（社区详情拉失败）就往下走按区筛，不让一次网络抖动变成空首页。
       */
      const bindNo = ctx.innermostNo ?? ctx.nearestNo;
      if (bindNo) {
        const c = await api.communityDetail(bindNo).catch(() => null);
        if (c) {
          await community.bind(c);
          // 落进围栏那一支距离为 0：顶栏据此**不显示**距离，不能把精确的说成「最近的」
          this.nearestDistanceM = ctx.innermostNo ? 0 : Math.max(ctx.nearestDistanceM, 0);
          return null;
        }
      }
      if (!ctx.regionCode) return null;
      this.coarseRegion = { code: ctx.regionCode, name: ctx.regionName ?? "" };
      return this.coarseRegion;
    },

    async load() {
      this.loading = true;
      try {
        const [active, list] = await Promise.all([
          api.activeAddress().catch(() => null),
          api.addressList().catch(() => [] as Address[]),
        ]);
        this.active = active;
        this.list = list;
        /*
         * **服务端说的生效位置，端上的归属要跟上。**
         *
         * 生效位置存在服务端（换设备、重装之后还是同一个上下文），
         * 而商品池挂在本地的 community 归属上。只读不同步的话，
         * 会出现「顶栏写着公司、商品还是家那边的」—— 两个都对，合起来是错的。
         * 实测撞到过：从别处切了位置，回到首页顶栏变了、商品没变。
         */
        if (this.active) await this.syncCommunityFromActive();
      } finally {
        this.loading = false;
      }
    },

    /**
     * 切到某个位置，并**把商品池跟着换过去**。
     *
     * <p><b>这一跳是「第一步不动主轴」的全部关键。</b>
     * 后端的商品池仍挂在 `communityNo` 上（商家可见性、佣金归属、
     * 配送范围判定都串在那根轴上），所以这里用地址的坐标去查
     * 「哪个已开通社区覆盖它」，再沿用现有的绑定链路。
     *
     * <p>将来第二步会去掉这一跳、由后端按坐标实时算 ——
     * 到那时**端上这个函数之外一行都不用改**，这正是分两步的理由。
     */
    /**
     * 当前定位**匹配到哪一条收货地址**。没有就是 null。
     *
     * <p>这是定位的**唯一用途**（PRD §6.1.0）：位置永远是一条地址，
     * 聚落匹配是那条地址的下游。定位不直接选聚落 ——
     * 那会让用户要理解两套东西，而归属、下单预填、送不送得到全都挂在地址上。
     *
     * <p>只取**足够近**的那条（1 公里内）。再远就不是「你在那儿」，
     * 而是「你附近碰巧存过一个地址」—— 按那个切会切到一个他此刻并不在的地方。
     */
    suggestNearest(at: { lat: number; lng: number }): Address | null {
      const latE6 = Math.round(at.lat * 1e6);
      const lngE6 = Math.round(at.lng * 1e6);
      let best: Address | null = null;
      let bestM = Number.POSITIVE_INFINITY;
      for (const a of this.list) {
        if (a.latE6 == null || a.lngE6 == null) continue;
        const m = metersBetweenE6(a.latE6, a.lngE6, latE6, lngE6);
        if (m < bestM) {
          best = a;
          bestM = m;
        }
      }
      return bestM <= MATCH_NEAR_M ? best : null;
    },

    /**
     * **以当前位置为准** —— 一条都没匹配上时走这里（PRD §6.1.0）。
     *
     * <p><b>不入地址簿、不写服务端</b>：它是这一次逛的上下文，不是一条资料。
     * 地址簿上限 20 条，每次「用一下现在这儿」都存一条会很快塞满；
     * 要不要存成地址，下单时再问。
     *
     * <p>「没匹配到」因此**不是死路**：他照样能逛、能下单。
     * 回落到「无位置首屏」只留给**连定位都拿不到**的情况。
     */
    async useTransient(at: { lat: number; lng: number }) {
      this.transientAt = at;
      const community = useCommunityStore();
      const list = await community.loadNearby(at.lat, at.lng).catch(() => [] as never[]);
      const ctx = await api
        .resolveLocation(Math.round(at.lat * 1e6), Math.round(at.lng * 1e6))
        .catch(() => null);
      const c = list.find((x) => x.communityNo === ctx?.innermostNo) ?? list[0];
      const p = c?.pickups?.[0];
      /*
       * **只要解析出聚落就绑**。此前是 `if (c && p)` ——
       * 聚落没有自提点时整个不绑，用户静默看不到任何货。
       * 点由下单时匹配，这里不再替他挑一个（那等于让数组顺序决定佣金归谁）。
       */
      if (c) await community.bind(c, p);
      /*
       * 解析不出地名时给一句「当前位置」而不是空串：顶栏那一行**任何时候都要有内容**，
       * 空着会让人以为页面没加载完，而这里恰恰是「已经切过去了」。
       */
      this.transientName = ctx?.innermostName ?? "";
      return { name: this.transientName, bound: !!(c && p) };
    },

    /**
     * 切到某个位置，并把商品池跟着换过去。
     *
     * <p><b>返回「归属有没有跟着换」</b> —— 没坐标的地址（微信导入、粘贴识别、
     * 存量手填）推不出聚落，此时 `syncCommunityFromActive` 什么都不做，这是对的
     * （清掉的话用户会发现「换了个地址，商品全没了」）。但**不能一声不吭**：
     * 他看到顶栏变了而商品没变，无从判断是坏了还是设计如此。
     * 调用方据此说一句「这个地址没有定位点，商品仍按 XX 显示」。
     */
    async switchTo(addressId: string) {
      const addr = await api.switchActiveAddress(addressId);
      this.active = addr;
      /*
       * **他自己点的，就听他的。** M9 定了顶栏跟当前定位，但那是「没人发话时的默认」；
       * 一个明确的动作被自动定位悄悄顶掉，比不跟定位更糟。
       * 不持久化 —— 下次打开又回到跟定位。
       */
      this.pickedByUser = true;
      /*
       * 切回地址簿里的某一条，这一次的「当前位置」就结束了。
       * 不清的话顶栏会一直挂着「当前位置 · XX」，而货已经按新地址换过了 ——
       * 顶栏说的和看到的不是一回事。
       */
      this.transientAt = null;
      this.transientName = "";
      const rebound = await this.syncCommunityFromActive();
      return { addr, rebound };
    },

    /**
     * 由生效位置的坐标解析出归属社区并绑定。
     *
     * <p><b>没有坐标就什么都不做</b>，而不是清掉现有归属：
     * 从微信地址簿导入的地址不带经纬度（`chooseAddress` 只给文字），
     * 那种位置照样是个有效的收货地址，只是推不出社区。
     * 清掉的话，用户会发现自己「换了个地址，商品全没了」。
     */
    /** @returns 归属有没有真的换过去。false = 这条地址没坐标，推不出聚落 */
    async syncCommunityFromActive(): Promise<boolean> {
      const a = this.active;
      if (!a || a.latE6 == null || a.lngE6 == null) return false;
      const community = useCommunityStore();
      const list = await community
        .loadNearby(a.latE6 / 1e6, a.lngE6 / 1e6)
        .catch(() => [] as never[]);
      /*
       * **「我在哪」由后端定，端上不再取第一条。**
       *
       * 判据是「层级优先于距离」（站在楼门口时，隔壁小区的中心可能比本楼中心更近）——
       * 那是业务规则。此前端上取 `list[0]`，等于把规则抄进了端，
       * 而 c-app / b-app / 将来的 H5 会各写一份，它们迟早不一样。
       *
       * 解析失败（新城区、模糊坐标）时回落到最近的那一条 —— 与改造前一致，
       * 不因为多了一次请求就让人看不到货。
       */
      const ctx = await api.resolveLocation(a.latE6, a.lngE6).catch(() => null);
      const c = list.find((x) => x.communityNo === ctx?.innermostNo) ?? list[0];
      const p = c?.pickups?.[0];
      // 同上：有聚落就绑得上，点交给下单时匹配
      if (c) {
        await community.bind(c, p);
        return true;
      }
      // 有坐标但一个聚落都没落进（新城区）—— 也算没换成，调用方同样要说一句
      return false;
    },
  },
});
