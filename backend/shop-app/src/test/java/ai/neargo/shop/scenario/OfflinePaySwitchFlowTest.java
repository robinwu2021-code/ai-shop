package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.merchant.service.StorePaySettingService;
import ai.neargo.shop.merchant.service.StorePaySettingService.PaySettingVO;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.PayModeService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 线下收款的两个商家开关（TDD-线下收款商家开关）。
 *
 * <p>2026-09-27 之前，{@code mch_store.offline_pay_enabled} 与 {@code prd_goods.pay_modes}
 * 自 V244 起<b>没有任何写入口</b>：判定层、下单、确认收款都在跑，却没有一家店开得出线下收款。
 * 所以这里断言的是<b>判定层真的给出 OFFLINE</b>（{@link PayModeService}），不是开关接口的返回值。
 *
 * <p>全部在店主会话的数据域下调 —— 两张表都带域，不带域的测试看不见「写了 0 行」那个坑。
 * 动到的共享种子（M0001 一家店的开关、一件商品的 pay_modes）在 {@link #restore()} 里还原。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("线下收款：门店开关 × 商品支付方式 → 判定层给出 OFFLINE")
class OfflinePaySwitchFlowTest {

    private static final String ENTITY = "M0001";
    /** 没有主体档案、没有证件的假主体 —— 自营免证那一支对它不成立 */
    private static final String BARE_ENTITY = "M-OFFPAY-BARE";
    private static final String BARE_STORE = "ST-OFFPAY-BARE";
    private static final String LICENSE_NO = "Q-OFFPAY-TEST";

    @Autowired private StorePaySettingService storePay;
    @Autowired private MerchantGoodsService goodsService;
    @Autowired private PayModeService payModeService;
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
        jdbc.update("update mch_store set offline_pay_enabled=0, cod_enabled=0 where store_no=?", store);
        jdbc.update("update prd_goods set pay_modes='[\"ONLINE\"]' where goods_no=?", goods);
        // M0001 在种子里是不是自营、有没有证都不该影响本组结论：补一张有效营业执照
        jdbc.update("insert into mch_qualification(qual_no, entity_no, qual_type, qual_name, status, created_at, updated_at)"
                + " values (?, ?, 'BUSINESS_LICENSE', '营业执照', 'VALID', now(), now())", LICENSE_NO, ENTITY);
        jdbc.update("insert into mch_store(entity_no, store_no, name, created_at, updated_at) values (?, ?, '无证测试店', now(), now())",
                BARE_ENTITY, BARE_STORE);
    }

    @AfterEach
    void restore() {
        jdbc.update("update mch_store set offline_pay_enabled=?, cod_enabled=? where store_no=?",
                storeBefore.get("offline_pay_enabled"), storeBefore.get("cod_enabled"), store);
        jdbc.update("update prd_goods set pay_modes=? where goods_no=?", payModesBefore, goods);
        jdbc.update("delete from mch_qualification where qual_no=?", LICENSE_NO);
        jdbc.update("delete from mch_store where store_no=?", BARE_STORE);
    }

    /** 与 /biz 请求里令牌过滤器设的是同一个域 */
    private static <T> T asMerchant(Supplier<T> body) {
        DataScopeContext.set(LoginUser.merchantByUser("U-OFFPAY-TEST", "店主").dataScope());
        try {
            return body.get();
        } finally {
            DataScopeContext.clear();
        }
    }

    private static ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (BizException e) {
            return e.errorCode();
        }
        return null;
    }

    @Test
    @DisplayName("★★★ 商品开 + 门店开 → 判定层给 OFFLINE；只开其一都不给（门店默认关）")
    void storeAndGoodsSwitchesOpenOfflinePay() {
        assertThat(payModeService.availablePayModes(goods, store)).containsExactly(PayModes.ONLINE);

        asMerchant(() -> goodsService.setPayModes(ENTITY, goods, List.of(PayModes.OFFLINE)));
        assertThat(jdbc.queryForObject("select pay_modes from prd_goods where goods_no=?", String.class, goods))
                .isEqualTo("[\"ONLINE\",\"OFFLINE\"]");
        // 商品愿意收，门店没开：仍然只有线上
        assertThat(payModeService.availablePayModes(goods, store)).containsExactly(PayModes.ONLINE);

        PaySettingVO v = asMerchant(() -> storePay.save(ENTITY, store, true, null));
        assertThat(v.offlinePayEnabled()).isTrue();
        assertThat(jdbc.queryForObject("select offline_pay_enabled from mch_store where store_no=?", Integer.class, store))
                .isEqualTo(1);
        assertThat(payModeService.availablePayModes(goods, store))
                .containsExactly(PayModes.ONLINE, PayModes.OFFLINE);
    }

    @Test
    @DisplayName("★★ 主体没有有效营业执照：开门店开关被拒 80012，库里仍是关")
    void enablingWithoutLicenseIsRejected() {
        assertThat(asMerchant(() -> storePay.get(BARE_ENTITY, BARE_STORE)).qualified()).isFalse();
        assertThat(codeOf(() -> asMerchant(() -> storePay.save(BARE_ENTITY, BARE_STORE, true, null))))
                .isEqualTo(ErrorCode.OFFLINE_PAY_NOT_QUALIFIED);
        assertThat(jdbc.queryForObject("select offline_pay_enabled from mch_store where store_no=?", Integer.class,
                BARE_STORE)).isZero();
    }

    @Test
    @DisplayName("★ 关线下收款连带关货到付款；线下没开就要开货到付款 = 400")
    void turningOfflineOffAlsoTurnsCodOff() {
        assertThat(codeOf(() -> asMerchant(() -> storePay.save(ENTITY, store, null, true))))
                .isEqualTo(ErrorCode.BAD_REQUEST);

        PaySettingVO on = asMerchant(() -> storePay.save(ENTITY, store, true, true));
        assertThat(on.codEnabled()).isTrue();

        PaySettingVO off = asMerchant(() -> storePay.save(ENTITY, store, false, null));
        assertThat(off.offlinePayEnabled()).isFalse();
        assertThat(off.codEnabled()).isFalse();
        assertThat(jdbc.queryForObject("select cod_enabled from mch_store where store_no=?", Integer.class, store))
                .isZero();
    }

    @Test
    @DisplayName("★ 支付方式：非法取值 400；恒含 ONLINE（只传 OFFLINE 也补上线上）")
    void payModesAreValidatedAndAlwaysKeepOnline() {
        assertThat(codeOf(() -> asMerchant(() -> goodsService.setPayModes(ENTITY, goods, List.of("CASH")))))
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(asMerchant(() -> goodsService.setPayModes(ENTITY, goods, List.of(PayModes.OFFLINE))))
                .containsExactly(PayModes.ONLINE, PayModes.OFFLINE);
        assertThat(asMerchant(() -> goodsService.setPayModes(ENTITY, goods, List.of())))
                .containsExactly(PayModes.ONLINE);
        assertThat(asMerchant(() -> goodsService.payModes(ENTITY, goods))).containsExactly(PayModes.ONLINE);
    }

    @Test
    @DisplayName("★ 别家的门店与商品：404，不是 403")
    void otherEntitysStoreIsNotFound() {
        assertThatThrownBy(() -> asMerchant(() -> storePay.save(BARE_ENTITY, store, true, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> asMerchant(() -> goodsService.setPayModes(BARE_ENTITY, goods, List.of(PayModes.OFFLINE)))))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
