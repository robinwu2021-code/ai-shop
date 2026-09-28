package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.product.service.PayModeService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 货到付款闭环的两道判定（TDD-货到付款闭环与门店级配送圆心）。
 *
 * <p>① 商家配送 × 线下 = 货到付款，要门店打开 {@code cod_enabled}。这条此前只写在两处注释里，
 * 下单与结算页都从没查过 —— 门店只开了「线下收款」，买家选商家配送也能当面付。
 * <p>② 配送圆心按订单落在的那家店取。此前一律取默认店：多门店商家的另一家店开了商家配送，
 * 永远按默认店的位置判送不送得到。
 *
 * <p>动到的共享种子（M0001 一家店的开关、一件商品的 pay_modes）在 {@link #restore()} 里还原；
 * 临时门店与证照自建自删。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("货到付款：门店开关 × 履约方式；配送圆心按订单门店")
class CodFlowTest {

    private static final String ENTITY = "M0001";
    private static final String LICENSE_NO = "Q-COD-TEST";
    private static final String FAR_STORE = "ST-COD-FAR";

    @Autowired private PayModeService payModeService;
    @Autowired private MerchantQueryPort merchantPort;
    @Autowired private PlatformConfigService platformConfig;
    @Autowired private JdbcTemplate jdbc;

    private String store;
    private String goods;
    private Map<String, Object> storeBefore;
    private String payModesBefore;

    @BeforeEach
    void seed() {
        store = jdbc.queryForObject("select store_no from mch_store where entity_no=? and deleted=0 order by id limit 1",
                String.class, ENTITY);
        goods = jdbc.queryForObject("select goods_no from prd_goods where entity_no=? and deleted=0 order by id limit 1",
                String.class, ENTITY);
        storeBefore = jdbc.queryForMap("select offline_pay_enabled, cod_enabled from mch_store where store_no=?", store);
        payModesBefore = jdbc.queryForObject("select pay_modes from prd_goods where goods_no=?", String.class, goods);
        // 商品愿意收线下、门店开了线下收款、主体有证 —— 只剩「履约方式 × 货到付款开关」这一层在变
        jdbc.update("update prd_goods set pay_modes='[\"ONLINE\",\"OFFLINE\"]' where goods_no=?", goods);
        jdbc.update("update mch_store set offline_pay_enabled=1, cod_enabled=0 where store_no=?", store);
        jdbc.update("insert into mch_qualification(qual_no, entity_no, qual_type, qual_name, status, created_at, updated_at)"
                + " values (?, ?, 'BUSINESS_LICENSE', '营业执照', 'VALID', now(), now())", LICENSE_NO, ENTITY);
    }

    @AfterEach
    void restore() {
        jdbc.update("update mch_store set offline_pay_enabled=?, cod_enabled=? where store_no=?",
                storeBefore.get("offline_pay_enabled"), storeBefore.get("cod_enabled"), store);
        jdbc.update("update prd_goods set pay_modes=? where goods_no=?", payModesBefore, goods);
        jdbc.update("delete from mch_qualification where qual_no=?", LICENSE_NO);
        jdbc.update("delete from mch_store where store_no=?", FAR_STORE);
    }

    @Test
    @DisplayName("★★★ 商家配送 × 线下：门店没开货到付款就不给；开了才给")
    void merchantDeliveryOfflineNeedsCodSwitch() {
        assertThat(payModeService.availablePayModes(goods, store, Fulfillments.MERCHANT_DELIVERY))
                .containsExactly(PayModes.ONLINE);

        jdbc.update("update mch_store set cod_enabled=1 where store_no=?", store);
        assertThat(payModeService.availablePayModes(goods, store, Fulfillments.MERCHANT_DELIVERY))
                .contains(PayModes.OFFLINE);
    }

    @Test
    @DisplayName("★★ 门店自提 × 线下不看货到付款开关；快递永远没有线下")
    void pickupIgnoresCodAndExpressNeverOffline() {
        assertThat(payModeService.availablePayModes(goods, store, Fulfillments.STORE_PICKUP))
                .contains(PayModes.OFFLINE);
        jdbc.update("update mch_store set cod_enabled=1 where store_no=?", store);
        assertThat(payModeService.availablePayModes(goods, store, Fulfillments.EXPRESS))
                .containsExactly(PayModes.ONLINE);
    }

    @Test
    @DisplayName("★★★ 快递 × 线下只在「快递测试模式」下放行，且只放快递这一种（TDD-快递100商家寄件 §7 AC10）")
    void expressOfflineOnlyUnderTestMode() {
        assertThat(payModeService.availablePayModes(goods, store, Fulfillments.EXPRESS))
                .as("正式环境：货寄出去之后没有当面收款的那一刻")
                .containsExactly(PayModes.ONLINE);
        try {
            platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, true, 0, "TEST");
            assertThat(payModeService.availablePayModes(goods, store, Fulfillments.EXPRESS))
                    .contains(PayModes.OFFLINE);
            assertThat(payModeService.availablePayModes(goods, store, Fulfillments.NEIGHBOR_PICKUP))
                    .as("测试模式只放快递：自提点代收货款照样是资金归集")
                    .containsExactly(PayModes.ONLINE);
        } finally {
            // 共享库：开关必须关回去，否则此后所有快递单都能线下付，而报错不会指向这里
            platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, false, 0, "TEST");
        }
    }

    @Test
    @DisplayName("★★★ 配送圆心按指定门店取：另一家店有自己的坐标与半径就用它的，不再一律取默认店")
    void deliveryOriginFollowsOrderStore() {
        jdbc.update("insert into mch_store(entity_no, store_no, name, lat_e6, lng_e6, delivery_radius_m, created_at, updated_at)"
                + " values (?, ?, '远处测试店', 22659412, 114039783, 5000, now(), now())", ENTITY, FAR_STORE);

        var far = merchantPort.deliveryOrigin(ENTITY, FAR_STORE).orElseThrow();
        assertThat(far.latE6()).isEqualTo(22659412);
        assertThat(far.lngE6()).isEqualTo(114039783);
        assertThat(far.radiusM()).isEqualTo(5000);

        // 不指定门店、或门店不属于这个主体：回落默认店（与改造前一致）
        assertThat(merchantPort.deliveryOrigin(ENTITY, null)).isEqualTo(merchantPort.deliveryOrigin(ENTITY));
        assertThat(merchantPort.deliveryOrigin("M-OTHER", FAR_STORE)).isEqualTo(merchantPort.deliveryOrigin("M-OTHER"));
    }
}
