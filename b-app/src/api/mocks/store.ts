// 门店：门面资料、经营范围与送货、店铺码、区域与取货点 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, db, delay, findGoodsSeed, persist, pick, toCommunity, toGoods } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import type { Store } from "@shared/types";
import { STORAGE } from "@shared/utils/constants";
import { money } from "@shared/utils/money";
import {
  MOCK_TIERS,
  SENSITIVE_WORDS,
  assertAssignable,
  currentStoreNo,
  flatCategories,
  logStaff,
  logStaffRole,
  maskPhone,
  minePlan,
  mockEstateCache,
  mockFulfillment,
  mockSelfBuilt,
  myGoods,
  newCommunitySeed,
  permLabel,
  requireMerchant,
  requireStaff,
  requireStore,
  roleName,
  storeOverrides,
  usersOfRole,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const storeMock: Pick<MerchantApi,
  "mStore"
  | "mScopePreview"
  | "mMasterData"
  | "mPayments"
  | "mPayChannels"
  | "mSubmitPayment"
  | "mOpenStorePayment"
  | "mRefreshPayment"
  | "mStoreCategories"
  | "mSaveStoreCategories"
  | "mEstates"
  | "mEstateCounts"
  | "mStoreFulfillment"
  | "mSaveStoreFulfillment"
  | "mStoreList"
  | "mMyStores"
  | "mEntities"
  | "mEntity"
  | "mCreateStore"
  | "mRenameStore"
  | "mSetStoreStatus"
  | "mSetDefaultStore"
  | "mSetStorePayment"
  | "mStaffList"
  | "mAddStaff"
  | "mSetStaffStatus"
  | "mBizScope"
  | "mGrantStore"
  | "mRoles"
  | "mRolePerms"
  | "mCreateRole"
  | "mUpdateRole"
  | "mDeleteRole"
  | "mStaffLogs"
  | "mCommunities"
  | "mRegions"
  | "mRegionSearch"
  | "mGeoReverse"
  | "mGeoTips"
  | "mRegionPath"
  | "mFulfillmentImpact"
  | "mPickupCandidates"
  | "mSelfBuildPickup"
  | "mVillageDict"
  | "mOpenCommunityFromMap"
  | "mApplyCommunity"
  | "mMyCommunityApplies"
  | "mSaveStore"
  | "mSaveAnnouncement"
  | "mDropNoticeRecent"
  | "mStoreQrcode"
  | "mShareKit"
  | "mPoster"
> = {
  // ---------------------------------------------------------------- 店铺与获客
  async mStore() {
    const cur = currentStoreNo();
    const store = cur ? db.stores.find((x) => x.storeNo === cur) : undefined;
    /*
     * 门面资料按**当前门店**：地址取那家店自己的，公告等改过的字段从按店覆盖里取。
     * 不分开的话，切到第二家店看到的是第一家的地址 —— 而那正是店主说的「没切过去」。
     */
    const out = {
      ...db.store,
      ...(store ? { address: store.address } : {}),
      ...(cur ? storeOverrides.get(cur) ?? {} : {}),
    } as typeof db.store & { announcementUntil?: number | null };
    // 与保存那一处同一条判断：过期的公告读出来就是空的
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out);
  },

  async mMasterData() {
    /*
     * mock 的行业白名单要**带一个不允许小微的行业**（线上服务），
     * 否则「行业决定能不能选小微」这条联动在 mock 下永远看不出效果，
     * 而它正是选错主体导致进件被拒的地方。
     */
    return delay({
      industries: [
        { industry: "FRESH", name: "生鲜果蔬", microAllowed: true },
        { industry: "GROCERY", name: "粮油日用", microAllowed: true },
        { industry: "BAKERY", name: "烘焙熟食", microAllowed: true },
        { industry: "ONLINE_SERVICE", name: "线上服务", microAllowed: false },
      ],
      subjects: [
        { subjectType: "NATURAL_PERSON" as const, name: "自然人", needLicense: false,
          industryGated: true, settleAccountType: "PERSONAL_BANK_CARD" as const },
        { subjectType: "INDIVIDUAL" as const, name: "个体工商户", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
        { subjectType: "ENTERPRISE" as const, name: "企业", needLicense: true,
          industryGated: false, settleAccountType: "MERCHANT_ID" as const },
      ],
      channels: [{ payChannel: "WECHAT", name: "微信支付", enabled: true, payMethods: ["JSAPI"] }],
      /*
       * **只给两档，与一期真实配置一致**（自营模式下 PLATFORM 没开）。
       * mock 里把三档全给上的话，「端上照下发的档位渲染」这件事就演示不出来 ——
       * 界面看着和写死三档完全一样，而真环境里第三档点下去会被拒。
       */
      serviceScopes: ["COMMUNITY", "CITY"] as const,
    });
  },

  /*
   * 主体级 + 门店级各一条。**同一个通道两行** —— 页面必须靠 storeNo 分得开，
   * 只给一条的话「多门店时这一页长什么样」永远看不到，
   * 而那正是这一块存在的理由。
   */
  async mPayments() {
    return delay([{ ...db.payment }, { ...db.storePayment }]);
  },

  /*
   * 能开的通道。**支付宝故意给一条 NONE**：「还没开通」是真实存在的状态，
   * mock 里两个通道都给成已开通的话，页面上「去开通」那一支永远走不到 ——
   * 而它正是商家第一次打开这一页要点的那个按钮。
   */
  async mPayChannels() {
    return delay([
      { ...db.payment },
      {
        payChannel: "ALIPAY",
        channelName: "支付宝",
        applyStatus: "NONE" as const,
        canReceiveMoney: false,
        missing: [],
        submitted: false,
        storeNo: "",
      },
    ]);
  },

  async mSubmitPayment(payload) {
    if (payload.entityNo && payload.entityNo === db.secondEntity.entityNo) {
      // 第二张证照还没交执照，进件进不了 —— 与后端一致：没证照没法进件
      throw new ApiError(10403, "这张证照还没交营业执照，先补上才能开通收款");
    }
    /*
     * mock 也走「资料齐了才通过」这条规则：恒成功的 mock 会让端上
     * 「缺什么就说缺什么」那段界面永远走不到，而它正是商家最需要的一段。
     */
    if (!payload.settleAccount) {
      throw new Error("还差结算账户");
    }
    const tail = payload.settleAccount.slice(-4);
    db.payment = {
      ...db.payment,
      applyStatus: "ACTIVE",
      canReceiveMoney: true,
      payMerchantNo: "PM-MOCK-0001",
      settleAccountType: payload.settleAccountType ?? "MERCHANT_ID",
      // 明文不进本地库 —— mock 也照这条来，免得端上养成读明文的习惯
      settleAccountMasked: `****${tail}`,
      missing: [],
      submitted: true,
      activatedAt: Date.now(),
    };
    persist();
    return delay({ ...db.payment });
  },

  async mOpenStorePayment(storeNo: string, payChannel?: string) {
    return {
      payChannel: payChannel ?? "WECHAT", channelName: "微信支付",
      applyStatus: "APPLYING", canReceiveMoney: false, missing: [],
      submitted: false, storeNo,
    };
  },

  async mRefreshPayment() {
    return delay({ ...db.payment });
  },

  async mStoreCategories(storeNo) {
    return delay((db.storeCategories[storeNo] ?? []).map((c) => ({ ...c })));
  },

  async mSaveStoreCategories(storeNo, items) {
    const before = db.storeCategories[storeNo] ?? [];
    /*
     * mock 也照真库拒：**撤掉一个底下还有商品的货架**要报错。
     * 恒成功的 mock 会让这条最常被触发的拒绝在开发期永远走不到 ——
     * 而它正是「店铺页里消失、商品列表里还在」那种状态的唯一防线。
     */
    const kept = new Set(items.map((i) => i.categoryNo));
    const blocked = before.find((c) => !kept.has(c.categoryNo) && c.goodsCount > 0);
    if (blocked) throw new Error(`「${blocked.name}」下还有 ${blocked.goodsCount} 件商品，请先移走`);

    const flat = flatCategories(db.categories);
    const next = items.map((i, idx) => {
      const platformName = flat.get(i.categoryNo)?.name ?? i.categoryNo;
      const old = before.find((c) => c.categoryNo === i.categoryNo);
      return {
        categoryNo: i.categoryNo,
        name: i.displayName?.trim() || platformName,
        platformName,
        displayName: i.displayName?.trim() || undefined,
        sort: i.sort ?? idx,
        // 三个数分开：在售/待审是「卖得怎么样」，goodsCount 是「能不能撤架」
        goodsCount: old?.goodsCount ?? 0,
        onSaleCount: old?.onSaleCount ?? 0,
        pendingCount: old?.pendingCount ?? 0,
      };
    });
    db.storeCategories[storeNo] = next;
    return delay(next.map((c) => ({ ...c })));
  },

  async mEstates(regionCode, opts) {
    const hit = mockEstateCache.get(regionCode);
    if (hit) return delay({ scopeCode: regionCode, items: hit.items, cached: true, stale: false });
    if (opts?.latE6 == null && opts?.lngE6 == null && !opts?.addressPath) {
      return delay({ scopeCode: regionCode, items: [], cached: false, stale: false });
    }
    const items: import("../contract").EstateItem[] = [
      { name: "模拟花园A区", address: "示例路 1 号", latE6: (opts?.latE6 ?? 22710000) + 200, lngE6: (opts?.lngE6 ?? 114030000) + 200 },
      { name: "模拟花园B区", address: "示例路 3 号", latE6: (opts?.latE6 ?? 22710000) - 200, lngE6: (opts?.lngE6 ?? 114030000) - 200 },
    ];
    mockEstateCache.set(regionCode, { parentCode: opts?.parentCode ?? "", items });
    return delay({ scopeCode: regionCode, items, cached: true, stale: false });
  },

  async mEstateCounts(parentCode) {
    const out: Record<string, number> = {};
    for (const [code, v] of mockEstateCache) {
      if (v.parentCode === parentCode) out[code] = v.items.length;
    }
    return delay(out);
  },

  // 门店送货方式（方案 v4）：mock 里每店一份，默认「自提两路开」——与生产播种同一映射
  async mStoreFulfillment(storeNo) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const saved = mockFulfillment[no];
    return delay(
      saved ?? {
        storeNo: no,
        channels: [
          { channel: "STORE_PICKUP", enabled: true, denied: false },
          { channel: "NEIGHBOR_PICKUP", enabled: true, denied: false },
          { channel: "MERCHANT_DELIVERY", enabled: false, denied: false },
          { channel: "EXPRESS", enabled: false, denied: false },
        ],
      },
    );
  },

  async mSaveStoreFulfillment(storeNo, payload) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const saved = mockFulfillment[no];
    // mock 也照写入口的硬规则拒：一路都不开的店等于开不了张
    if (!payload.channels.some((c) => c.enabled)) {
      throw new Error("至少开启一种送货方式");
    }
    const next = {
      storeNo: no,
      channels: payload.channels.map((c) => ({
        channel: c.channel as import("@shared/types").FulfillmentType,
        enabled: c.enabled,
        denied: false,
        templateNo: c.templateNo ?? null,
        pickups: c.channel === "NEIGHBOR_PICKUP"
          ? (c.pickupNos
              ? c.pickupNos.map((no) => {
                  const own = mockSelfBuilt.find((p) => p.pickupNo === no);
                  return { pickupNo: no, name: own?.name ?? no, address: own?.address ?? null, type: "STORE" as const, status: own?.status ?? "ACTIVE" };
                })
              : saved?.channels.find((x) => x.channel === "NEIGHBOR_PICKUP")?.pickups ?? [])
          : undefined,
      })),
    };
    mockFulfillment[no] = next;
    return delay({ ...next });
  },

  async mStoreList() {
    return delay(db.stores.map((s) => ({ ...s })));
  },

  /**
   * 按证照分组的门店。**与 mStoreList 是两个范围**：那个只给当前证照，这个给全部。
   *
   * <p>第一组是当前证照（种子里的「张记粮油」），第二组是老板的第二门生意
   * （`db.secondEntity`，待补证照）。没有第二组的话，分组这件事在 mock 下看不出来。
   */
  async mMyStores() {
    return delay([
      {
        entity: {
          entityNo: db.merchant.merchantNo,
          name: db.merchant.name,
          status: db.merchant.status,
          verified: db.merchant.status === "ACTIVE",
          storeCount: db.stores.length,
          isPrimary: true,
          canManage: true,
        },
        stores: db.stores.map((s) => ({ ...s })),
      },
      {
        entity: { ...db.secondEntity, storeCount: db.secondEntityStores.length },
        stores: db.secondEntityStores.map((s) => ({ ...s })),
      },
    ]);
  },

  async mEntities() {
    const groups = await this.mMyStores();
    // 只给「我是持有人」的那些。mock 里两张都是他自己的
    return delay(groups.filter((g) => g.entity.canManage).map((g) => ({ ...g.entity })));
  },

  async mEntity(entityNo) {
    const groups = await this.mMyStores();
    const hit = groups.find((g) => g.entity.entityNo === entityNo);
    // 与真库同一口径：不是我的证照 → 拒，**不是回落到当前那张**
    if (!hit) throw new ApiError(10403, "这张证照不属于你");
    return delay(hit);
  },

  async mCreateStore(payload) {
    /*
     * mock 也照额度拒。恒成功的 mock 会让「超额」那段界面永远走不到，
     * 而它是多门店里最常被触发的一条路径 —— FREE 档只能有一家店。
     */
    /*
     * 挂到第二张证照下时，撞的是**那张的额度**，不是当前这张的 ——
     * 与后端一致（mch_entity_plan 挂在 entity_no 上）。mock 里第二张给 3 家，
     * 好让「挂到另一张下能建成」这条路走得通。
     */
    const onSecond = !!payload.entityNo && payload.entityNo === db.secondEntity.entityNo;
    if (payload.entityNo && !onSecond && payload.entityNo !== db.merchant.merchantNo) {
      throw new ApiError(10403, "这张证照不属于你");
    }
    const bucket = onSecond ? db.secondEntityStores : db.stores;
    /*
     * **额度只有一个来源**：套餐页与这道闸都走 `minePlan()`。
     *
     * 此前这里读的是 `db.storeQuota`（种子原值，恒为 1），而套餐页读的是
     * `minePlan()` —— 它认 `mock:plan` 这个运行时覆盖。于是切到 PRO 之后，
     * 页头写着「成长版 · 门店 1/3」，点保存却被拒「当前套餐最多 1 家门店」，
     * **两处都「是真的」，谁也说不清哪个错** —— 而 MOCK_TIERS 上方那段注释
     * 警告的正是这件事。
     *
     * 后果不只是别扭：多门店在 mock 下**根本建不出第二家店**，
     * 于是「切了店页面没跟着变」这类缺陷在 mock 上永远复现不了。
     */
    const quota = onSecond ? 3 : minePlan().storeQuota;
    if (bucket.length >= quota) {
      throw new Error(`当前套餐最多 ${quota} 家门店`);
    }
    const store = {
      storeNo: onSecond ? `ST-MOCK-E2-${bucket.length + 1}` : `ST-MOCK-${bucket.length + 1}`,
      name: payload.name,
      address: payload.address ?? "",
      isDefault: bucket.length === 0,
      status: "ACTIVE" as const,
      payReady: true,
      staffCount: 0,
    };
    bucket.push(store);
    persist();
    return delay({ ...store });
  },

  async mRenameStore(storeNo, payload) {
    const s = requireStore(storeNo);
    s.name = payload.name || s.name;
    if (payload.address !== undefined) s.address = payload.address;
    persist();
    return delay({ ...s });
  },

  async mSetStoreStatus(storeNo, active) {
    const s = requireStore(storeNo);
    // 默认店不能停用 —— 停掉之后「这个主体的店在哪」就没有答案了
    if (!active && s.isDefault) throw new Error("默认店不能停用，请先把默认标转给别家");
    s.status = active ? "ACTIVE" : "READONLY";
    persist();
    return delay({ ...s });
  },

  async mSetDefaultStore(storeNo) {
    const s = requireStore(storeNo);
    if (s.status !== "ACTIVE") throw new Error("已停用的店不能设为默认");
    db.stores.forEach((x) => { x.isDefault = x.storeNo === storeNo; });
    persist();
    return delay({ ...s });
  },

  async mSetStorePayment(storeNo, payMerchantNo) {
    const s = requireStore(storeNo);
    // 传空 = 回到主体默认号，是合法操作不是清空错误
    s.payMerchantNo = payMerchantNo || undefined;
    persist();
    return delay({ ...s });
  },

  async mStaffList() {
    return delay(db.staff.map((x) => ({ ...x })));
  },

  async mAddStaff(loginPhone, displayName) {
    if (!/^\d{11}$/.test(loginPhone)) throw new Error("请填 11 位手机号");
    const existing = db.staff.find((x) => x.loginPhone === loginPhone);
    if (existing) {
      // 离职再回来是常事：重新启用而不是报「已存在」
      existing.status = "ACTIVE";
      // 对老板来说这就是「把人加回来」，所以记 STAFF_ADD 而不是 ENABLE ——
      // 审计要还原他做了什么，不是还原代码走了哪个分支
      logStaff(existing, "STAFF_ADD", undefined, undefined,
        `重新启用已存在的员工 ${maskPhone(loginPhone)}`);
      persist();
      return delay({ ...existing });
    }
    const staff = {
      mchAccountNo: `SF-MOCK-${db.staff.length + 1}`,
      displayName: displayName?.trim() || undefined,
      // 号码就是登录用户名，完整存 —— 与后端同口径
      loginPhone,
      isOwner: false,
      status: "ACTIVE" as const,
      roles: [],
    };
    db.staff.push(staff);
    logStaff(staff, "STAFF_ADD", undefined, undefined, `新增员工 ${maskPhone(loginPhone)}`);
    persist();
    return delay({ ...staff });
  },

  async mSetStaffStatus(mchAccountNo, active) {
    const st = requireStaff(mchAccountNo);
    // 老板不能被停用 —— 那是个能把自己锁在门外的按钮
    if (st.isOwner && !active) throw new Error("老板不能被停用");
    st.status = active ? "ACTIVE" : "DISABLED";
    logStaff(st, active ? "STAFF_ENABLE" : "STAFF_DISABLE", undefined, undefined,
      active ? "启用员工" : "停用员工（门店授权保留）");
    persist();
    return delay({ ...st });
  },

  async mBizScope() {
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    // mock 里的演示会话恒为老板 —— 要体验受限角色请连真后端用员工账号登录。
    // 这里不编一个「假的店员」：那会让开发期看到的裁剪结果与真实的不一样
    return delay({
      merchantNo: db.merchant.merchantNo,
      currentStoreNo: home?.storeNo ?? "",
      owner: true,
      storeNos: db.stores.map((s) => s.storeNo),
      pickupNos: db.merchant.isPickupPoint ? ["PP-MOCK-1"] : [],
      groupNos: [],
      staffRoles: ["OWNER"],
      perms: ["*"],
      /*
       * **只给两张证**，不给全集：给全了「缺资质」这条路在开发期永远走不到，
       * 而它正是类目选择器上那个角标要表达的东西。
       * mock 商家能卖蔬菜与预包装食品，卖不了酒、肉、奶粉。
       */
      categoryCodes: ["FRESH_VEG", "PACKAGED_FOOD"],
      /*
       * **平台开关。此前替身根本不给这个字段**，于是 `switches` 恒为空对象，
       * 所有跟开关有关的分支在开发期一条都走不到 —— 而它们恰恰是最难在真环境
       * 里凑出来的那些（要改配置重启，或让运营去拨一次）。
       *
       * 两个都给 false，与线上当前一致：
       * · `categoryGate` —— 类目资质拦不拦；
       * · `stockByInventory` —— 库存真相源是不是进销存
       *   （`stock-authority=INVENTORY`；线上现在是 DUAL，所以 false）。
       *
       * **要验另一支就把它改成 true**，这是替身存在的意义之一。
       */
      switches: { categoryGate: false, stockByInventory: false },
    });
  },

  async mGrantStore(mchAccountNo, storeNo, role, granted) {
    const st = requireStaff(mchAccountNo);
    const store = requireStore(storeNo);
    /*
     * **增量式：只动这一个角色**（一人一店可多角色）。
     *
     * 原先是先把这家店的角色全 filter 掉再 push 一个 —— 那是覆盖式，
     * 老板想「再加一个配送员」会把「店员」冲掉，而且不报错。
     * mock 与后端必须同一套语义，否则开发期看到的是另一个产品。
     */
    const had = st.roles.some((r) => r.storeNo === storeNo && r.role === role);
    st.roles = st.roles.filter((r) => !(r.storeNo === storeNo && r.role === role));
    if (granted !== false) st.roles.push({ storeNo, storeName: store.name, role });
    // 撤销一个他本来就没有的角色是空操作，不留痕 —— 与后端同口径，
    // 否则日志里会出现一串「撤销了店长」而他从来不是店长
    if (granted !== false) {
      logStaff(st, "ROLE_GRANT", store.name, role, `授予 ${store.name} 的 ${roleName(role)}`);
    } else if (had) {
      logStaff(st, "ROLE_REVOKE", store.name, role, `撤销 ${store.name} 的 ${roleName(role)}`);
    }
    persist();
    return delay({ ...st });
  },

  /**
   * 员工与授权的变更记录（B-11.10.3）。倒序 —— 最近做的那一件最可能是要查的。
   */
  /**
   * 角色列表：6 个预置（只读）+ 自定义。
   *
   * 预置那份**与后端 V71 的 seed 同一套语义** —— mock 里编一份不一样的，
   * 开发期看到的角色能力就与真实的不同，而这正是最不该分岔的地方。
   */
  async mRoles() {
    return delay(db.roles.map((r) => ({ ...r, usedBy: usersOfRole(r.roleCode) })));
  },

  /**
   * 可勾的权限点：**db.permLabels 全表减掉 `biz:store:admin`** ——
   * 与后端 `BizPerms.assignableCodes()` 同一条口径（那边也是全表减一条）。
   */
  async mRolePerms() {
    return delay(
      Object.entries(db.permLabels)
        .filter(([code]) => code !== "biz:store:admin")
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([code, label]) => ({ code, label })),
    );
  },

  async mCreateRole(payload) {
    const perms = assertAssignable(payload.perms);
    const role = {
      roleCode: `R-MOCK-${db.roles.length + 1}`,
      name: payload.name.trim(),
      builtin: false,
      perms,
      permLabels: perms.map(permLabel),
      usedBy: 0,
    };
    db.roles.push(role);
    logStaffRole("ROLE_CREATE", role.roleCode, `新建角色「${role.name}」`);
    persist();
    return delay({ ...role });
  },

  async mUpdateRole(roleCode, payload) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    // 预置只读：与后端同口径，要改先复制一份
    if (role.builtin) throw new Error("平台预置角色不可修改，请复制为自定义角色");
    const perms = assertAssignable(payload.perms);
    role.name = payload.name.trim();
    role.perms = perms;
    role.permLabels = perms.map(permLabel);
    logStaffRole("ROLE_UPDATE", roleCode, `角色「${role.name}」权限已更新`);
    persist();
    return delay({ ...role, usedBy: usersOfRole(roleCode) });
  },

  async mDeleteRole(roleCode) {
    const role = db.roles.find((r) => r.roleCode === roleCode);
    if (!role) throw new Error("角色不存在");
    if (role.builtin) throw new Error("平台预置角色不可删除");
    const used = usersOfRole(roleCode);
    // 还有人在用就不许删 —— 删了那些人的权限凭空消失，而他们看不到任何解释
    if (used > 0) throw new Error(`还有 ${used} 人在用这个角色，先把他们撤下来`);
    db.roles = db.roles.filter((r) => r.roleCode !== roleCode);
    logStaffRole("ROLE_DELETE", roleCode, `删除角色「${role.name}」`);
    persist();
    return delay(undefined as unknown as void);
  },

  async mStaffLogs(mchAccountNo) {
    const all = db.staffLogs ?? [];
    return delay(
      [...all]
        .filter((l) => !mchAccountNo || l.targetAccountNo === mchAccountNo)
        .sort((a, b) => b.at - a.at),
    );
  },

  async mCommunities() {
    return delay(allCommunitySeeds().map(toCommunity));
  },

  async mRegions(parent) {
    // 恒定只给启用的 —— 与后端 /biz/regions 同口径（停用的是运营的维护对象）
    return delay(
      db.regionSeeds.filter((r) => r.enabled && (parent ? r.parentCode === parent : !r.parentCode)),
    );
  },

  /**
   * 村名词典。mock 里给北山街道配了两条官方村级（regionSeeds），
   * 词典就查它们 —— 与后端同口径：按街道过滤 + 名称包含。
   */
  // ---- P1：跨级搜索 / 路径 / 关路清单 / 取货点 ----
  async mRegionSearch(kw) {
    const q = (kw ?? "").trim();
    const pathOf = (code?: string): string => {
      const chain: string[] = [];
      let cur = code ? db.regionSeeds.find((r) => r.regionCode === code) : undefined;
      while (cur) {
        chain.unshift(cur.name);
        cur = cur.parentCode ? db.regionSeeds.find((r) => r.regionCode === cur!.parentCode) : undefined;
      }
      return chain.join(" / ");
    };
    /*
     * **四级都搜（省也搜），并且按级配额** —— 与后端 RegionService#search 同一口径。
     * 曾经这里和后端都把省排除在外、又共用一份 LIMIT，于是搜「山西」一条也没有、
     * 搜「运城」被街道占满；mock 不跟着改的话，开发期永远复现不出这两件事。
     */
    const QUOTA: Record<string, number> = { PROVINCE: 3, CITY: 5, DISTRICT: 8, STREET: 8 };
    const strength = (name: string) => (name === q ? 0 : name.startsWith(q) ? 1 : 2);
    const regions = !q ? [] : Object.keys(QUOTA).flatMap((level) => db.regionSeeds
      .filter((r) => r.enabled && r.level === level && r.name.includes(q))
      .sort((a, b) => strength(a.name) - strength(b.name) || a.regionCode.localeCompare(b.regionCode))
      .slice(0, QUOTA[level])
      .map((r) => ({ regionCode: r.regionCode, level: r.level, name: r.name, path: pathOf(r.parentCode) })));
    const communities = q.length < 2 ? [] : allCommunitySeeds().map(toCommunity)
      .filter((c) => c.name.includes(q))
      .slice(0, 30)
      // parentNo 要带上：少了它，搜出来的楼栋在整个小区已勾中时仍显示成「没选上」
      .map((c) => ({ communityNo: c.communityNo, name: c.name, regionCode: c.regionCode,
                     parentNo: c.parentNo, path: pathOf(c.regionCode) }));
    // 还没开通的官方村：与后端同口径 —— 已开通的走 communities，这里不重复出
    const openedNames = new Set(communities.map((c) => c.name));
    const villages = q.length < 2 ? [] : db.regionSeeds
      .filter((r) => r.level === "VILLAGE" && r.enabled && r.name.includes(q) && !openedNames.has(r.name))
      .slice(0, 20)
      .map((r) => ({
        regionCode: r.regionCode, name: r.name,
        streetCode: r.parentCode ?? "", path: pathOf(r.parentCode),
        latE6: null, lngE6: null,
      }));
    return delay({ regions, communities, villages });
  },

  async mGeoReverse(lat, lng) {
    return delay({ recommend: `阳光里小区南门（${lat.toFixed(4)}, ${lng.toFixed(4)}）`, address: "浙江省杭州市西湖区阳光里" });
  },

  async mGeoTips(kw) {
    const q = kw.trim();
    if (!q) return delay([]);
    // 两条带坐标、一条不带：端上要把没坐标的提示过滤掉
    return delay([
      { name: `${q}花园`, address: "西湖区文三路 88 号", adcode: "330106", latE6: 30279000, lngE6: 120131000, typecode: "120302" },
      { name: `${q}公寓`, address: "西湖区文二路 12 号", adcode: "330106", latE6: 30281000, lngE6: 120128000, typecode: "120302" },
      { name: `${q}路`, address: "西湖区", adcode: "330106", latE6: null, lngE6: null, typecode: "190301" },
    ]);
  },

  async mRegionPath(code) {
    const chain: import("@shared/types").Region[] = [];
    let cur = db.regionSeeds.find((r) => r.regionCode === code);
    while (cur) {
      chain.unshift(cur);
      cur = cur.parentCode ? db.regionSeeds.find((r) => r.regionCode === cur!.parentCode) : undefined;
    }
    return delay(chain.filter((r) => r.level !== "VILLAGE"));
  },

  async mFulfillmentImpact(_storeNo, channel) {
    const four = new Set(["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS"]);
    return delay(
      myGoods()
        .filter((g) => g.onSale)
        .filter((g) => {
          const ways = ((g as { fulfillments?: string[] }).fulfillments ?? []).filter((w) => four.has(w));
          return ways.length === 1 && ways[0] === channel;
        })
        .map((g) => ({ goodsNo: g.goodsNo, title: g.title })),
    );
  },

  async mPickupCandidates(storeNo) {
    const no = storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : storeNo;
    const mine = mockSelfBuilt.filter((p) => p.ownerStoreNo === no);
    const nearby: import("@shared/types").PickupCandidate[] = allCommunitySeeds().flatMap((c) => {
      const vo = toCommunity(c);
      return (vo.pickups ?? []).map((p) => ({
        pickupNo: p.pickupNo,
        name: p.name,
        address: p.address,
        type: "STORE" as const,
        status: "ACTIVE",
        communityNo: vo.communityNo,
        communityName: vo.name,
        ownerStoreNo: null,
      }));
    });
    return delay([...mine, ...nearby]);
  },

  async mSelfBuildPickup(payload) {
    const no = payload.storeNo === "default" ? db.stores[0]?.storeNo ?? "ST-MOCK-1" : payload.storeNo;
    if (mockSelfBuilt.some((p) => p.ownerStoreNo === no && p.name === payload.name.trim())) {
      throw new Error("这个取货点已经提交过了");
    }
    const created: import("@shared/types").PickupCandidate = {
      pickupNo: `PK${Date.now()}`,
      name: payload.name.trim(),
      address: payload.address.trim(),
      type: "STORE",
      status: "PENDING",
      communityNo: payload.communityNo ?? allCommunitySeeds()[0]!.communityNo,
      communityName: toCommunity(allCommunitySeeds()[0]!).name,
      ownerStoreNo: no,
    };
    mockSelfBuilt.unshift(created);
    return delay(created);
  },

  async mVillageDict(street, keyword) {
    const kw = (keyword ?? "").trim();
    return delay(
      db.regionSeeds
        .filter((r) => r.parentCode === street && r.level === "VILLAGE")
        .filter((r) => !kw || r.name.includes(kw))
        .slice(0, 50),
    );
  },

  async mOpenCommunityFromMap(payload) {
    /*
     * mock 也照真库查重：**同名就复用，不新建**。
     * 恒新建的 mock 会让「同一个小区被建成两条」这个最要命的后果在开发期永远走不到。
     * （坐标那道闸在真库里跑，mock 的种子没有坐标，比不了。）
     */
    const exist = allCommunitySeeds().map(toCommunity).find((c) => c.name === payload.name);
    if (exist) return delay(exist);
    const seed = newCommunitySeed(payload.name, payload.address, payload.streetCode, "ESTATE");
    db.communityOpened.push(seed);
    persist();
    return delay(toCommunity(seed));
  },

  async mApplyCommunity(payload) {
    const merchantNo = requireMerchant();
    if (db.communityApplies.some((a) => a.name === payload.name && a.status === "PENDING")) {
      // 与后端同口径：重复提报不会让它更快通过，只会让运营的队列里多一条一样的
      throw new Error("这个小区你已经提报过，正在等运营处理");
    }
    const apply = {
      applyNo: `CA${Date.now()}`,
      // 聚落模型：kind 与定位随提报走，通过时带进聚落
      kind: payload.kind === "VILLAGE" ? "VILLAGE" : "ESTATE",
      originCode: payload.originCode,
      latE6: payload.latE6,
      lngE6: payload.lngE6,
      merchantNo,
      merchantName: (() => {
        const n = db.merchantSeeds.find((m) => m.merchantNo === merchantNo)?.name;
        return n ? pick(n) : merchantNo;
      })(),
      ...payload,
      status: "PENDING" as const,
      submittedAt: Date.now(),
    };
    /*
     * **官方名录里的村免审直开**（与后端 submitApply 同口径）：名录本身就是权威，
     * 再让运营点一次「通过」只是把商家晾在那儿等一天。台账仍然留一条 APPROVED 的记录。
     * mock 不照做的话，端上「点一下村＝加入范围」这条路在开发期永远停在「等运营处理」。
     */
    if (payload.originCode) {
      const seed = newCommunitySeed(payload.name, payload.address, payload.regionCode, "VILLAGE");
      db.communityOpened.push(seed);
      const opened = { ...apply, status: "APPROVED" as const, communityNo: seed.communityNo };
      db.communityApplies.unshift(opened);
      persist();
      return delay({ ...opened });
    }
    db.communityApplies.unshift(apply);
    persist();
    return delay({ ...apply });
  },

  async mMyCommunityApplies() {
    const merchantNo = requireMerchant();
    return delay(db.communityApplies.filter((a) => a.merchantNo === merchantNo));
  },

  /*
   * 范围预览 mock。**要真的按传进来的那一份算**，不是回一对好看的数：
   * 回常量的话「改成这样会多覆盖几个」在 mock 下永远是同一个值，
   * 而这一屏存在的理由就是那个差值。
   *
   * 口径与后端对齐的三条：框了小区盖住它下面的楼、排除项减掉、只自提且没有纳入项 = 0。
   */
  async mScopePreview(areas) {
    const seeds = allCommunitySeeds();
    const expand = (list: typeof areas) => {
      const inc = (list ?? []).filter((a) => a.mode !== "EXCLUDE");
      const exc = new Set<string>();
      for (const a of (list ?? []).filter((x) => x.mode === "EXCLUDE")) {
        exc.add(a.refCode);
        for (const c of seeds) if (c.parentNo === a.refCode) exc.add(c.communityNo);
      }
      const out = new Set<string>();
      for (const a of inc) {
        out.add(a.refCode);
        for (const c of seeds) if (c.parentNo === a.refCode) out.add(c.communityNo);
      }
      for (const x of exc) out.delete(x);
      return out;
    };
    const pickupOnly = (db.store.fulfillmentReach ?? "PICKUP") === "PICKUP";
    const nextSet = expand(areas);
    const curSet = expand((db.store.serviceAreas ?? []) as typeof areas);
    // 只自提 + 没有纳入项 = 谁也看不到（与后端 includes.isEmpty() 那一支同口径）
    const size = (s: Set<string>, list: typeof areas) =>
      (pickupOnly && !(list ?? []).some((a) => a.mode !== "EXCLUDE")) ? 0 : s.size;
    const buyers = (s: Set<string>) => [...s].filter((no) => seeds.some((c) => c.communityNo === no)).length;
    return delay({
      currentCommunities: size(curSet, (db.store.serviceAreas ?? []) as typeof areas),
      currentBuyers: buyers(curSet),
      nextCommunities: size(nextSet, areas),
      nextBuyers: buyers(nextSet),
    });
  },

  async mSaveStore(payload) {
    /*
     * 先脱响应式外壳（同 mSaveGoods）：`serviceAreas` 是页面 `form.value` 里的
     * reactive 代理数组，而 `delay()` 用 structuredClone 返回副本 —— Chrome **拒绝克隆 Proxy**，
     * 于是保存经营范围会弹一句「Failed to execute 'structuredClone'…」，
     * 商家看到的是保存失败，而他什么也没做错。深拷贝一次＝HTTP 上的 JSON 往返。
     */
    const clean = JSON.parse(JSON.stringify(payload)) as typeof db.store;
    const cur = currentStoreNo();
    if (cur) {
      /*
       * 多门店：改的是**这一家**的门面，不能顺手把另一家的公告也改了。
       *
       * ★ 但经营范围是**主体级**的（这一页自己写着「全部门店共用」），
       * 整包塞进按店覆盖里有两个后果：切到另一家店看不到刚保存的范围，
       * 而按店覆盖又是个不落盘的内存 Map —— 刷新一次范围就回到种子值。
       * 后者会把「排除项没读回来」这种真缺陷伪装成 mock 的正常表现，
       * 反过来也一样：真的丢了，也看不出来。主体级的字段写回 db.store 并落盘。
       */
      const { serviceAreas, serviceScope, fulfillmentReach, serviceCommunityNos, ...perStore } = clean;
      storeOverrides.set(cur, perStore as Partial<typeof db.store>);
      db.store = { ...db.store, serviceAreas, serviceScope, fulfillmentReach, serviceCommunityNos };
      persist();
    } else {
      db.store = clean;
      persist();
    }
    /*
     * **过期即空**：与后端 `MchStore.effectiveAnnouncement()` 同一条判断。
     * 只在真库里做的话，「昨天到货挂到今天」这个最要紧的后果在 mock 上看不见。
     */
    const out = { ...clean } as typeof db.store & { announcementUntil?: number | null };
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out);
  },

  async mSaveAnnouncement(payload) {
    /*
     * 与真库同口径：只动公告与有效期，**不碰门面其它字段**；
     * 「常用」由服务端维护（去重 + 最近的排最前 + 最多 5 条）。
     */
    const st = db.store as typeof db.store & {
      announcementUntil?: number | null; announcementRecent?: string[];
    };
    const now = (payload.announcement ?? "").trim();
    /*
     * 机审：**命中不是拒绝，是转人审**（与真库同一条判断）。
     * 命中期间旧公告原样留着 —— 清空的话店铺页会突然变白，
     * 店主以为自己改坏了，只会反复再改一遍。
     */
    const hit = SENSITIVE_WORDS.find((w) => now.includes(w));
    if (hit) {
      (st as { noticePending?: unknown }).noticePending = { content: now, submittedAt: Date.now() };
      persist();
      return delay({ ...st } as typeof db.store);
    }
    (st as { noticePending?: unknown }).noticePending = null;
    st.announcementRecent = [now, ...(st.announcementRecent ?? []).filter((x) => x && x !== now)]
      .filter(Boolean).slice(0, 8);
    st.announcement = now;
    st.announcementUntil = payload.announcementUntil ?? null;
    /*
     * 「同时发到」别的门店：mock 只有一份 db.store，写不出多店的效果 ——
     * 这里只把它记下来，让端上的勾选有个回声。多店的真实行为由后端用例守
     * （ServiceAreaFlowTest.announcementFansOutToPickedStores）。
     */
    (st as { alsoStoreNos?: string[] }).alsoStoreNos = payload.alsoStoreNos ?? [];
    persist();
    const out = { ...st };
    if (out.announcementUntil && out.announcementUntil < Date.now()) out.announcement = "";
    return delay(out as typeof db.store);
  },

  async mDropNoticeRecent(text) {
    const st = db.store as typeof db.store & { announcementRecent?: string[] };
    st.announcementRecent = (st.announcementRecent ?? []).filter((x) => x && x !== text);
    persist();
    return delay({ ...st } as typeof db.store);
  },

  async mStoreQrcode() {
    const merchantNo = requireMerchant();
    /*
     * **一店一码**（V298）：码属于当前这家门店，不是主体。
     *
     * 此前这里只返回 url，连 storeCode 都没有 —— 于是「码是哪家店的」这件事
     * 在 mock 上根本演不出来，而它正是多门店店主印之前唯一能发现贴错店的机会。
     */
    /*
     * **跟着当前门店走**。mock 不走 HTTP，读不到 X-Store-No，
     * 但 pinia 切店时会把门店号落到 `STORAGE.storeNo` —— 读同一个键即可，
     * 与真后端「从请求头拿当前门店」是同一件事的两种实现。
     *
     * 早先这里写死取第一家，注释写的是「mock 里没有当前门店这个概念」——
     * 那句话当时成立（多门店在 mock 下建不出来，无从分辨），
     * 但它恰恰会让「切了店、码没跟着变」这个缺陷在 mock 上演不出来。
     */
    const current = (uni.getStorageSync(STORAGE.storeNo) as string) || "";
    const store = db.stores.find((x) => x.storeNo === current) ?? db.stores[0];
    const storeNo = store?.storeNo ?? null;
    const storeCode = storeNo ? `shop_${storeNo}` : `shop_${merchantNo}`;
    // 落地页带 storeCode —— 真后端扫码走 by-code，靠它解出是哪家店
    const url = `/pages/store/index?storeCode=${storeCode}`;
    return delay({ merchantNo, storeCode, storeNo, url, imageBase64: null });
  },

  async mShareKit(goodsNo) {
    const merchantNo = requireMerchant();
    const name = db.merchant.name || "我的小店";
    if (goodsNo) {
      const g = toGoods(findGoodsSeed(goodsNo));
      return delay({
        text: `【${name}】${g.title} ${money(g.price)}，到店自提或送货上门，点开直接下单`,
        posterUrl: "",
      });
    }
    return delay({
      text: `【${name}】开在你家楼下，常买的东西点两下就能再来一单：/pages/store/index?merchantNo=${merchantNo}`,
      posterUrl: "",
    });
  },

  async mPoster() {
    requireMerchant();
    // 品牌色占位块，只为了让「真海报」这条 UI 分支在 mock 下也有图可看——
    // 真实合成（封面/店名/价格/小程序码）只在后端 PosterService 里发生
    return delay({
      imageBase64:
        "iVBORw0KGgoAAAANSUhEUgAAAHgAAAC0CAIAAADQLH9KAAAA/UlEQVR42u3QMQ0AAAgDsPngxr8mnOACniZV0EwXB6JAtGhEixZtQbRoRIsWbUG0aESLFo1o0YgWLRrRohEtWjSiRSNatGhEi0a0aNGIFo1o0aIRLRrRokUjWjSiRYtGtGhEixaNaNGIFi0a0aIRLVo0okUjWrRoRItGtGjRiBaNaNGiES0a0aJFI1o0okWLRrRoRIsWjWjRiBYtGtGiES1aNKJFI1q0aESLRrRo0YgWjWjRohEtGtGiRSNaNKJFi0a0aESLFo1o0YgWLRrRohEtWjSiRSNatGhEi0a0aNGIFo1o0aIRLRrRokUjWjSiRYtGtGhEixaNaNGI/rHYbkXySmIOegAAAABJRU5ErkJggg==",
    });
  },
};
