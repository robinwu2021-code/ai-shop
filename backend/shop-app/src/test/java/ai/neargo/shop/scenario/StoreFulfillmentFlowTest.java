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
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null),
                new ChannelCmd(Fulfillments.EXPRESS, true, "FT-X")));
        var vo = fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null),
                new ChannelCmd(Fulfillments.EXPRESS, false, "FT-X")));
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
                new ChannelCmd(Fulfillments.STORE_PICKUP, false, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("save：门店没有地址却开门店自取 → 拦（没有地址的自取是空承诺）")
    void saveRejectsStorePickupWithoutAddress() {
        String m = merchant(null);
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("save：值域外的 channel（含服务类两值）→ 拦")
    void saveRejectsBadChannel() {
        String m = merchant("文三路 1 号");
        assertThatThrownBy(() -> fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_VERIFY, true, null))))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("可见性：EXPRESS 开着 = 原 SHIPPING —— 全部开放社区可达")
    void expressReachesAllOpenCommunities() {
        String c = openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.EXPRESS, true, null)));
        assertThat(merchantQuery.reachableCommunities(m)).contains(c);
    }

    @Test
    @DisplayName("可见性：只有自提且没框范围 = 原 PICKUP —— 谁也看不到")
    void pickupOnlyWithoutAreasReachesNobody() {
        openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null)));
        assertThat(merchantQuery.reachableCommunities(m)).isEmpty();
    }

    @Test
    @DisplayName("可见性：自送开着且没框范围 = 原 ONSITE —— 不限")
    void deliveryWithoutAreasReachesAll() {
        String c = openCommunity();
        String m = merchant("文三路 1 号");
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null)));
        assertThat(merchantQuery.reachableCommunities(m)).contains(c);
    }

    @Test
    @DisplayName("enabledFulfillments：门店级取该店、storeNo 空取主体并集、未迁移返回空集")
    void enabledSetSemantics() {
        String m = merchant("文三路 1 号");
        // 未迁移（无行）：空集 —— 调用方按旧口径放行
        assertThat(merchantQuery.enabledFulfillments(m, null)).isEmpty();
        fulfillmentService.save(m, null, List.of(
                new ChannelCmd(Fulfillments.STORE_PICKUP, true, null),
                new ChannelCmd(Fulfillments.EXPRESS, false, null)));
        assertThat(merchantQuery.enabledFulfillments(m, null))
                .containsExactly(Fulfillments.STORE_PICKUP);
    }
}
