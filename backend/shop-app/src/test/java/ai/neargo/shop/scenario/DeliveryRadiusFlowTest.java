package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService.ChannelCmd;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.mapper.UserMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家自送的「半径」必须真的拦得住单。
 *
 * <p><b>这条规则此前只存在于界面上</b>：商家在「送货方式 › 商家自送」里填 3000 米，
 * `mch_store.delivery_radius_m` 有存有取，但<b>全仓没有任何一处拿它算过距离</b> ——
 * 连 {@code OUT_OF_DELIVERY_RANGE(20003)} 这个错误码都定义了很久却从没被抛过。
 * 于是多远的单都会进来，商家要等到准备送货时才发现送不到，而那时钱已经收了。
 *
 * <p>三条一起测，因为**放行的那两条比拦截更容易写错**：拿缺失数据去拦，
 * 会把本来正常的单挡在门外，而那种故障在生产上表现为「买家莫名其妙下不了单」。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeliveryRadiusFlowTest {

    private static final String MERCHANT = "M0001";
    private static final String BUYER = "U0001";

    /** 门店：深圳市民中心 */
    private static final int STORE_LAT = 22_543_099;
    private static final int STORE_LNG = 114_057_900;
    /** 约 400 米外（半径 3000 内） */
    private static final int NEAR_LAT = 22_546_700;
    private static final int NEAR_LNG = 114_057_900;
    /** 约 11 公里外（半径 3000 外） */
    private static final int FAR_LAT = 22_643_099;
    private static final int FAR_LNG = 114_057_900;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private UserMappers.AddressMapper addressMapper;

    @Autowired
    private StoreFulfillmentService fulfillmentService;

    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;

    /*
     * **物理删除，不能用 mapper.delete。**
     * FulfillmentChannel 带 @TableLogic，`delete` 只把行标成 deleted=1，
     * 而 `uk_store_channel(tenant_no, store_no, channel)` 里没有 deleted ——
     * 于是下一个用例再开同一路通道时撞唯一键（实测 DuplicateKeyException）。
     * 这与 prd_category_spec 那处是同一个形状。
     */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** enableSelfDelivery 动手之前 G0001 原本的履约声明，用完放回去 */
    private String goodsFulfillmentsBackup;

    /**
     * **把动过的共享种子还原。**
     *
     * <p>这个类为了走到半径这一层，改了两处**全局种子**：把 M0001 的门店履约通道
     * 整份替换成只剩「商家自送」，并把 G0001 的 fulfillments 改成两项。
     * 两处都不还原的话，后面每一个从种子商家下 STORE_PICKUP 单的用例都会被
     * 闸二拒掉 70013 ——「所选商品不支持该配送方式」。
     *
     * <p>实测代价：全量 1348 条里有 **68 条**红在这上面，占清单一大半。
     * 而它们**单独跑全是绿的** —— 这类故障最难认领：每个人在自己那一类里跑都好好的，
     * 只有全量才红，于是所有人都以为是别人的问题。
     */
    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
        DataScopeContext.executeWithoutScope(() -> {
            // 通道行删干净 = 回到「这家店还没迁移到 channel 模型」，闸二按空集放行
            jdbc.update("DELETE FROM mch_fulfillment_channel WHERE entity_no = ?", MERCHANT);
            if (goodsFulfillmentsBackup != null) {
                PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, "G0001").last("limit 1"));
                if (g != null) {
                    g.setFulfillments(goodsFulfillmentsBackup);
                    goodsMapper.updateById(g);
                }
            }
            return null;
        });
        goodsFulfillmentsBackup = null;
    }

    @Test
    @DisplayName("★★ 收货地址超出自送半径 → 下单直接被拒，而不是收了钱再退")
    void farAddressIsRejected() {
        enableSelfDelivery();
        store(STORE_LAT, STORE_LNG, 3000);
        String addressId = address(FAR_LAT, FAR_LNG);
        asBuyer();

        assertThatThrownBy(() -> orderService.create(deliveryOrder(addressId), key("far")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_DELIVERY_RANGE);
    }

    @Test
    @DisplayName("范围内的地址照常下单 —— 这条不过，等于把所有自送单都拦死了")
    void nearAddressPasses() {
        enableSelfDelivery();
        store(STORE_LAT, STORE_LNG, 3000);
        String addressId = address(NEAR_LAT, NEAR_LNG);
        asBuyer();

        assertThatNotRejectedForRange(addressId, "near");
    }

    @Test
    @DisplayName("★ 门店没标点 / 地址没坐标 → 放行。拿缺失数据拦单，会挡住本来正常的买家")
    void missingCoordinatesPass() {
        // 门店有坐标、买家地址是手填的（没坐标）
        enableSelfDelivery();
        store(STORE_LAT, STORE_LNG, 3000);
        String handTyped = address(null, null);
        asBuyer();
        assertThatNotRejectedForRange(handTyped, "hand");

        // 反过来：地址有坐标（远在天边），但门店从没在地图上标过点
        store(null, null, 3000);
        String far = address(FAR_LAT, FAR_LNG);
        assertThatNotRejectedForRange(far, "nopin");

        // 半径 0 = 不限距离，同样放行
        store(STORE_LAT, STORE_LNG, 0);
        assertThatNotRejectedForRange(far, "nolimit");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 下单链路上还有库存、支付能力等一串校验，这个测试只关心「有没有因为超范围被拒」。
     * 所以断言的是**不抛 OUT_OF_DELIVERY_RANGE**，而不是「一定下单成功」——
     * 后者会把这条测试变成整条交易链路的镜子，别人改任何一处都来找它。
     */
    private void assertThatNotRejectedForRange(String addressId, String keySuffix) {
        try {
            orderService.create(deliveryOrder(addressId), key(keySuffix));
        } catch (BizException e) {
            assertThat(e.errorCode())
                    .as("不该因为「超出配送范围」被拒：%s", e.getMessage())
                    .isNotEqualTo(ErrorCode.OUT_OF_DELIVERY_RANGE);
        }
    }

    private OrderService.CreateOrderCommand deliveryOrder(String addressId) {
        return new OrderService.CreateOrderCommand(
                List.of(new OrderService.CreateOrderCommand.Item("G0001", "SK0001", 1)),
                Fulfillments.MERCHANT_DELIVERY, null, addressId, null, 0L, null, null, null, null, null);
    }

    /** 幂等键要各测各的，否则第二次调用会直接拿到第一次的结果，校验根本没跑 */
    private String key(String suffix) {
        return "test-radius-" + suffix + "-" + System.nanoTime();
    }

    private void asBuyer() {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.CONSUMER, ai.neargo.shop.auth.SubjectKind.USR, BUYER, "测试买家",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    /**
     * 开这家店的「商家自送」通道。不开的话下单会先撞上 FULFILLMENT_NOT_SUPPORTED，
     * 半径这一层根本走不到 —— 测出来的绿是假的。
     */
    private void enableSelfDelivery() {
        fulfillmentService.save(MERCHANT, null, List.of(
                new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null)));
        // 商品自己也要声明支持自送：商品快照那道闸在门店通道之前，不放开就走不到半径这一层
        DataScopeContext.executeWithoutScope(() -> {
            PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getGoodsNo, "G0001").last("limit 1"));
            // 先记下原样 —— 这是全局种子，@AfterEach 要放回去
            if (goodsFulfillmentsBackup == null) {
                goodsFulfillmentsBackup = g.getFulfillments();
            }
            g.setFulfillments("[\"STORE_PICKUP\",\"MERCHANT_DELIVERY\"]");
            return goodsMapper.updateById(g);
        });
    }

    /**
     * 默认门店的坐标与自送半径。
     *
     * <p><b>用 update wrapper 显式 set，不用 {@code updateById}</b>：后者按 MyBatis-Plus
     * 的默认策略**跳过 null 字段**，于是「门店没标过点」这一档根本没把坐标清掉，
     * 测出来是上一档留下的坐标 —— 这条测试第一版就是这么假绿的。
     */
    private void store(Integer latE6, Integer lngE6, int radiusM) {
        DataScopeContext.executeWithoutScope(() -> {
            MchStore s = storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getEntityNo, MERCHANT)
                    .orderByDesc(MchStore::getIsDefault).last("limit 1"));
            return storeMapper.update(null, Wrappers.<MchStore>lambdaUpdate()
                    .eq(MchStore::getId, s.getId())
                    .set(MchStore::getLatE6, latE6)
                    .set(MchStore::getLngE6, lngE6)
                    .set(MchStore::getDeliveryRadiusM, radiusM));
        });
    }

    /** 买家地址簿里插一条，返回 addressId */
    private String address(Integer latE6, Integer lngE6) {
        String id = "AD-RADIUS-" + System.nanoTime();
        UsrAddress a = new UsrAddress();
        a.setAddressId(id);
        a.setUserNo(BUYER);
        a.setName("测试收货人");
        a.setPhone("13800000000");
        a.setRegion("广东省深圳市福田区");
        a.setDetail("测试路 1 号");
        a.setIsDefault(false);
        a.setLatE6(latE6);
        a.setLngE6(lngE6);
        DataScopeContext.executeWithoutScope(() -> addressMapper.insert(a));
        return id;
    }
}
