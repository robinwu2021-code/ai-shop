package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService.ChannelCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门店送货方式（方案 v4）：channel 挂门店 + 可见性/上架/下单三道闸换数据源。
 *
 * <p>守的是两件事：<b>写入口的硬规则</b>（一路不开/自取无地址拦截），
 * 以及<b>可见性映射与旧模型一致</b>——EXPRESS=原 SHIPPING 全开放、
 * 只有自提且没范围=谁也看不到。后者错了不报错，只会「商品谁也搜不到」。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreFulfillmentFlowTest {

    @Autowired
    private StoreFulfillmentService fulfillmentService;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    @Autowired
    private ai.neargo.shop.spi.user.CommunityQueryPort communityQuery;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    @Autowired
    private ai.neargo.shop.community.service.CommunityService communityService;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.FulfillmentChannelMapper channelMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    @Autowired
    private ai.neargo.shop.merchant.service.MerchantStoreService storeService;

    @Autowired
    private ai.neargo.shop.community.service.CommunityAdminService communityAdminService;

    private static int seq = 7000;

    /** 造主体 + 默认门店。reach 留空 —— 这组用例只走新模型 */
    private String merchant(String address) {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("履约测试");
        m.setStatus("ACTIVE");
        m.setLegalForm("ENTERPRISE");
        merchantMapper.insert(m);
        var s = new ai.neargo.shop.merchant.entity.MchStore();
        s.setEntityNo(m.getEntityNo());
        s.setStoreNo("SFT" + seq++);
        s.setName("履约测试店");
        s.setIsDefault(true);
        s.setStatus("ACTIVE");
        s.setAddress(address);
        storeMapper.insert(s);
        return m.getEntityNo();
    }

    private String openCommunity() {
        var c = new ai.neargo.shop.community.entity.CmtCommunity();
        c.setCommunityNo("SFC" + seq++);
        c.setName("履约测试小区");
        c.setStatus("OPEN");
        c.setFenceRadius(1000);
        communityMapper.insert(c);
        return c.getCommunityNo();
    }

    @Test
    @DisplayName("get：没配过也返回固定四行，全部关着 —— 端上不用自己补缺")
    void getReturnsFourRows() {
        String m = merchant("文三路 1 号");
        var vo = fulfillmentService.get(m, null);
        assertThat(vo.channels()).hasSize(4);
        assertThat(vo.channels()).allMatch(c -> !c.enabled());
        assertThat(vo.channels()).extracting("channel").containsExactly(
                Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP,
                Fulfillments.MERCHANT_DELIVERY, Fulfillments.EXPRESS);
    }

    @Test
    @DisplayName("save：开关落库，关一路配置保留（enabled=0 不是删行）")
    void saveTogglesWithoutLosingRows() {
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.EXPRESS, true, "FT-X", null, null, null)));
        var vo = fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.EXPRESS, false, "FT-X", null, null, null)));
        var express = vo.channels().stream()
                .filter(c -> Fulfillments.EXPRESS.equals(c.channel())).findFirst().orElseThrow();
        assertThat(express.enabled()).isFalse();
        // 模板号还在 —— 再打开时不用重配
        assertThat(express.templateNo()).isEqualTo("FT-X");
    }

    @Test
    @DisplayName("save：一路都不开 → 拦（一路不开的店等于开不了张）")
    void saveRejectsAllOff() {
        String m = merchant("文三路 1 号");
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, false, null, null, null, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("save：门店没有地址却开门店自取 → 拦（没有地址的自取是空承诺）")
    void saveRejectsStorePickupWithoutAddress() {
        String m = merchant(null);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("save：值域外的 channel（含服务类两值）→ 拦")
    void saveRejectsBadChannel() {
        String m = merchant("文三路 1 号");
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_VERIFY, true, null, null, null, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("可见性：EXPRESS 开着 = 原 SHIPPING —— 全部开放社区可达")
    void expressReachesAllOpenCommunities() {
        String c = openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.EXPRESS, true, null, null, null, null)));
        assertThat(merchantQuery.reachableCommunities(m)).contains(c);
    }

    @Test
    @DisplayName("可见性：只有自提且没框范围 = 原 PICKUP —— 谁也看不到")
    void pickupOnlyWithoutAreasReachesNobody() {
        openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null)));
        assertThat(merchantQuery.reachableCommunities(m)).isEmpty();
    }

    @Test
    @DisplayName("可见性：自送开着且没框范围 = 原 ONSITE —— 不限")
    void deliveryWithoutAreasReachesAll() {
        String c = openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null)));
        assertThat(merchantQuery.reachableCommunities(m)).contains(c);
    }

    @Test
    @DisplayName("enabledFulfillments：门店级取该店、storeNo 空取主体并集、未迁移返回空集")
    void enabledSetSemantics() {
        String m = merchant("文三路 1 号");
        // 未迁移（无行）：空集 —— 调用方按旧口径放行
        assertThat(merchantQuery.enabledFulfillments(m, null)).isEmpty();
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.EXPRESS, false, null, null, null, null)));
        assertThat(merchantQuery.enabledFulfillments(m, null))
                .containsExactly(Fulfillments.STORE_PICKUP);
    }

    // ---------------------------------------------------------------- 取货点（P1）

    @Test
    @DisplayName("自建点：落 PENDING，本店可引用；别家的待审点对你不存在")
    void selfBuiltPickupIsPendingAndOnlyOwnerCanReference() {
        String m = merchant(null);
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String community = openCommunity();
        var built = communityService.selfBuildPickup(new ai.neargo.shop.community.service.CommunityService.SelfBuildCmd(
                store, "东门驿站", "东门 12 号", 30_000_000, 120_000_000, "08:00-20:00", community, null));
        assertThat(built.status()).isEqualTo("PENDING");
        assertThat(built.ownerStoreNo()).isEqualTo(store);

        // 本店：没地址、只开社区自提、引用了自建点 → 可以保存，且 get 带出 PENDING 的引用
        var vo = fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, List.of(built.pickupNo()), null, null)));
        var neighbor = vo.channels().stream()
                .filter(c -> Fulfillments.NEIGHBOR_PICKUP.equals(c.channel())).findFirst().orElseThrow();
        assertThat(neighbor.pickups()).singleElement()
                .satisfies(r -> {
                    assertThat(r.pickupNo()).isEqualTo(built.pickupNo());
                    assertThat(r.status()).isEqualTo("PENDING");
                });

        // 别家：引用这个待审点 → 拒
        String other = merchant(null);
        assertThatThrownBy(() -> fulfillmentService.save(other, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, List.of(built.pickupNo()), null, null))))
                .isInstanceOf(BizException.class);

        // 同店同名重复提交 → CONFLICT，不生出第二条待审
        assertThatThrownBy(() -> communityService.selfBuildPickup(new ai.neargo.shop.community.service.CommunityService.SelfBuildCmd(
                store, "东门驿站", "别处", 30_000_000, 120_000_000, null, community, null)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("自提开着却一个落点都没有（没地址、没引用）→ 写入口拦")
    void pickupWithoutAnyLandingIsRejected() {
        String m = merchant(null);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, List.of(), null, null))))
                .isInstanceOf(BizException.class);
        // 新开这一路、没带引用、没地址 → 同样拦
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, null, null, null))))
                .isInstanceOf(BizException.class);
        // 存量已开（播种）、没地址、这次只是重发开关不碰引用 → 放行：否则每次开关保存都被拒
        var seeded = merchant(null);
        var row = new ai.neargo.shop.merchant.entity.MchFulfillmentChannel();
        row.setStoreNo(merchantQuery.defaultStoreNo(seeded).orElseThrow());
        row.setEntityNo(seeded);
        row.setChannel(Fulfillments.NEIGHBOR_PICKUP);
        row.setEnabled(true);
        row.setScopeMode(ai.neargo.shop.merchant.entity.MchFulfillmentChannel.SCOPE_ALL);
        channelMapper.insert(row);
        assertThat(fulfillmentService.save(seeded, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null))).channels())
                .anyMatch(c -> Fulfillments.MERCHANT_DELIVERY.equals(c.channel()) && c.enabled());
        // 有门店地址就行：门店自己就是落点
        String withAddr = merchant("文三路 1 号");
        assertThat(fulfillmentService.save(withAddr, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, List.of(), null, null))).channels())
                .anyMatch(c -> Fulfillments.NEIGHBOR_PICKUP.equals(c.channel()) && c.enabled());
    }

    @Test
    @DisplayName("运营裁决：通过 → ACTIVE 进候选与下单白名单；驳回要理由；裁完不能再裁")
    void decidePickupThenCandidatesAndAllowed() {
        String m = merchant(null);
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String community = openCommunity();
        var built = communityService.selfBuildPickup(new ai.neargo.shop.community.service.CommunityService.SelfBuildCmd(
                store, "南门驿站", "南门 1 号", 30_000_000, 120_000_000, null, community, null));

        assertThatThrownBy(() -> communityAdminService.decidePickup(built.pickupNo(), false, " ", "OPS"))
                .isInstanceOf(BizException.class);
        var ok = communityAdminService.decidePickup(built.pickupNo(), true, null, "OPS");
        assertThat(ok.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> communityAdminService.decidePickup(built.pickupNo(), true, null, "OPS"))
                .isInstanceOf(BizException.class);

        // 候选：本店自建的排最前
        var candidates = communityService.pickupCandidates(List.of(community), store);
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).pickupNo()).isEqualTo(built.pickupNo());

        // 下单白名单：门店自己的 STORE 点天然在内；引用之后也在
        assertThat(merchantQuery.allowedPickupNos(m)).contains(built.pickupNo());
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.NEIGHBOR_PICKUP, true, null, List.of(built.pickupNo()), null, null)));
        assertThat(merchantQuery.allowedPickupNos(m)).contains(built.pickupNo());
    }

    // ---------------------------------------------------------------- P2：锁路 / 范围子集

    @Test
    @DisplayName("锁路：锁着的路商家改不了开关，原样回传放行；买家侧不可选；解锁恢复")
    void opsLockBlocksMerchantAndBuyer() {
        String m = merchant("文三路 1 号");
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null)));
        fulfillmentService.setLocked(store, Fulfillments.MERCHANT_DELIVERY, true);

        assertThat(fulfillmentService.get(m, null).channels())
                .filteredOn(c -> Fulfillments.MERCHANT_DELIVERY.equals(c.channel()))
                .allSatisfy(c -> assertThat(c.locked()).isTrue());
        assertThat(merchantQuery.enabledFulfillments(m, store)).doesNotContain(Fulfillments.MERCHANT_DELIVERY);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, false, null, null, null, null))))
                .isInstanceOf(BizException.class);
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null),
                new ChannelCmd(Fulfillments.EXPRESS, true, null, null, null, null)));
        fulfillmentService.setLocked(store, Fulfillments.MERCHANT_DELIVERY, false);
        assertThat(merchantQuery.enabledFulfillments(m, store)).contains(Fulfillments.MERCHANT_DELIVERY);
    }

    @Test
    @DisplayName("★★★ 切口径：A 店的货不再出现在只有 B 店服务的社区里")
    void goodsOfStoreAIsNotVisibleInCommunityOnlyStoreBServes() {
        String m = merchant("文三路 5 号");
        String storeA = merchantQuery.defaultStoreNo(m).orElseThrow();
        String cmA = openCommunity();
        String cmB = openCommunity();

        // 主体足迹两块都要（子集是从主体足迹里挑的）
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", cmA),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", cmB))));
        String areaA = areaNoOf(m, cmA);

        // A 店的自送只覆盖 cmA
        fulfillmentService.save(m, storeA, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaA))));

        /*
         * ★ 这就是方案 §2.1 那个场景的核心断言。
         *
         * 改造之前可见性取的是**主体并集**（enabledFulfillments(merchantNo, null)），
         * 所以 A 店的货会同时进 cmA 和 cmB 的池 —— 而 A 店根本不送 cmB。
         * cmB 的买家搜到它、下了单，货送不到。
         *
         * 撤掉 reachableCommunities 的门店裁剪，这一条必须立刻红。
         */
        assertThat(merchantQuery.reachableCommunities(m, storeA))
                .as("A 店只服务 cmA").containsExactly(cmA);
        assertThat(merchantQuery.reachableCommunities(m, storeA))
                .as("A 店不该把货带进它不送的 cmB").doesNotContain(cmB);
    }

    /** 取这个主体在这个社区上的 service_area 主键 —— 配 SUBSET 时要引它 */
    private String areaNoOf(String merchantNo, String communityNo) {
        return serviceAreaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getRefCode, communityNo))
                .get(0).getAreaNo();
    }

    @Test
    @DisplayName("★★ 按门店算可达：ALL 门店与主体口径**逐字相等** —— 这条钉住「今天零行为变化」")
    void storeReachEqualsEntityReachWhenAllScope() {
        String m = merchant("文三路 2 号");
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String c1 = openCommunity();
        String c2 = openCommunity();
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c1),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c2))));
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null)));

        /*
         * ★ 线上今天**没有任何门店配过 SUBSET**（2026-08-25 查生产：SUBSET 0 条、
         * mch_channel_area 0 行），所以全部走 ALL 分支。这条断言就是「切口径当天
         * 零行为变化」的形式化：门店口径与主体口径必须给出同一个集合。
         *
         * 它红了就说明新口径在**存量数据上**已经与旧的不等价 —— 那时不该往下走第 3 步。
         */
        assertThat(merchantQuery.reachableCommunities(m, store))
                .as("没配 SUBSET 的门店，可达集合必须与主体口径相等")
                .containsExactlyInAnyOrderElementsOf(merchantQuery.reachableCommunities(m));
        assertThat(merchantQuery.reachableCommunities(m, store)).contains(c1, c2);

        // 不传门店 = 主体口径，与加这个重载之前逐字相同
        assertThat(merchantQuery.reachableCommunities(m, null))
                .containsExactlyInAnyOrderElementsOf(merchantQuery.reachableCommunities(m));
    }

    @Test
    @DisplayName("★★ 按门店算可达：配了 SUBSET 的门店只到自己那几块，而主体口径仍是全部")
    void subsetStoreOnlyReachesItsOwnAreas() {
        String m = merchant("文三路 3 号");
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String inside = openCommunity();
        String outside = openCommunity();
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", inside),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", outside))));
        String areaInside = serviceAreaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m)
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getRefCode, inside))
                .get(0).getAreaNo();

        // 这家店的自送只覆盖 inside 一块
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaInside))));

        assertThat(merchantQuery.reachableCommunities(m, store))
                .as("门店口径只算它自己覆盖的那几块").containsExactly(inside);
        /*
         * ★ **主体口径不能跟着变**。商家详情页问的是「这家商家覆盖哪儿」，
         * 那是主体级问题；跟着门店收窄的话，一家开了两个片区的商家会在自己主页上
         * 只显示其中一片。
         */
        assertThat(merchantQuery.reachableCommunities(m))
                .as("主体口径仍是全部足迹").contains(inside, outside);
    }

    @Test
    @DisplayName("★★★ 「没配 SUBSET」不是「空子集」—— 混成一个的话全平台商品当场消失")
    void noSubsetIsNotAnEmptySubset() {
        String m = merchant("文三路 4 号");
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String c1 = openCommunity();
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", c1))));
        // 只开自提，scope_mode 走默认（ALL）—— 这就是线上每一家店今天的样子
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null)));

        /*
         * 实现里 storeSubsetAreaNos 返回 null 表示「这家店没在做子集这件事」，
         * 返回空集表示「配了 SUBSET 但一块都没勾」。两者混成一个的后果不对称：
         * 把 null 当空集 → **今天线上每一家店都被算成一块都不覆盖**，商品全线消失。
         * 所以这条单独钉一次。
         */
        assertThat(merchantQuery.reachableCommunities(m, store))
                .as("没配 SUBSET 的店 = 覆盖主体全足迹，不是覆盖零")
                .containsExactly(c1);
    }

    @Test
    @DisplayName("范围子集：只能引用自己的范围项；按买家社区裁剪；EXPRESS 不允许收窄")
    void subsetNarrowsByBuyerCommunity() {
        String m = merchant("文三路 1 号");
        String store = merchantQuery.defaultStoreNo(m).orElseThrow();
        String inside = openCommunity();
        String outside = openCommunity();
        storeService.save(m, new ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, List.of(
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", inside),
                        new ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand("COMMUNITY", outside))));
        String areaInside = serviceAreaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, m)
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getRefCode, inside))
                .get(0).getAreaNo();

        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null, null, null, null),
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaInside))));
        assertThat(merchantQuery.enabledFulfillmentsFor(m, store, inside)).contains(Fulfillments.MERCHANT_DELIVERY);
        assertThat(merchantQuery.enabledFulfillmentsFor(m, store, outside)).doesNotContain(Fulfillments.MERCHANT_DELIVERY);
        assertThat(merchantQuery.enabledFulfillmentsFor(m, store, null)).contains(Fulfillments.MERCHANT_DELIVERY);

        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of("SA-NOT-MINE")))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.EXPRESS, true, null, null, "SUBSET", List.of(areaInside)))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of()))))
                .isInstanceOf(BizException.class);
        var vo = fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "ALL", null)));
        assertThat(vo.channels()).filteredOn(c -> Fulfillments.MERCHANT_DELIVERY.equals(c.channel()))
                .allSatisfy(c -> { assertThat(c.scopeMode()).isEqualTo("ALL"); assertThat(c.areaNos()).isEmpty(); });
    }
}
