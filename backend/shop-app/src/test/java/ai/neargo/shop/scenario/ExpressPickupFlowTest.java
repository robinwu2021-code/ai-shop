package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.merchant.service.StoreShipSettingService;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import ai.neargo.shop.trade.entity.OrdExpressPickup;
import ai.neargo.shop.trade.service.ExpressPickupService;
import ai.neargo.shop.trade.service.ExpressPickupService.PickupVO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 快递代下单（TDD-快递100商家寄件）的验收用例。
 *
 * <p>通道换成 {@link FakePort}：签名是 {@code GOOD} 才算验过，报文是 {@code 状态|运单号|运费分}。
 * 真实的签名与报文解析在 {@code Kuaidi100PickupGatewayTest} 里单独验。
 *
 * <p>数据全用自建的测试商家 {@link #ENTITY}，自建自删，不碰共享种子。
 */
@SpringBootTest
// 换了通道 bean = 另一个 Spring 上下文，要一套独立内存库（见 application-expresspickup.yml）
@ActiveProfiles({"test", "expresspickup"})
@DisplayName("快递代下单：下单、回调、取件回填运单号、运费记欠款")
class ExpressPickupFlowTest {

    static final String ENTITY = "M-EXP-T";
    static final String STORE = "ST-EXP-T";
    static final String SUB = "SUB-EXP-T1";
    static final String ORDER = "SO-EXP-T1";

    static final AtomicBoolean ENABLED = new AtomicBoolean(true);
    /** 假通道记下最近一次收到的环境与下单报文 —— 断言「走了哪个环境」「寄件人是谁」用 */
    static final AtomicReference<Boolean> LAST_SANDBOX = new AtomicReference<>();
    static final AtomicReference<ExpressPickupPort.CreateCmd> LAST_CREATE = new AtomicReference<>();

    @TestConfiguration
    static class FakeChannel {
        @Bean
        @Primary
        ExpressPickupPort fakeExpressPickupPort() {
            return new FakePort();
        }
    }

    static class FakePort implements ExpressPickupPort {
        @Override
        public boolean enabled() {
            return ENABLED.get();
        }

        @Override
        public List<String> carriers() {
            return List.of("ZTO", "YTO", "JD");
        }

        @Override
        public Optional<Quote> quote(String carrier, String from, String to, int weightG, boolean sandbox) {
            LAST_SANDBOX.set(sandbox);
            return switch (carrier) {
                case "ZTO" -> Optional.of(new Quote("ZTO", 900, 1500));
                case "YTO" -> Optional.of(new Quote("YTO", 700, 1500));
                default -> Optional.empty();   // 京东这条线不接 —— 报价单里不该出现它
            };
        }

        @Override
        public Booked create(CreateCmd cmd) {
            LAST_CREATE.set(cmd);
            LAST_SANDBOX.set(cmd.sandbox());
            return new Booked(true, "T-" + cmd.thirdOrderNo(), "O-" + cmd.thirdOrderNo(), null, "ok");
        }

        @Override
        public Booked cancel(String taskId, String orderId, String reason, boolean sandbox) {
            LAST_SANDBOX.set(sandbox);
            return new Booked(true, taskId, orderId, null, "ok");
        }

        @Override
        public Optional<Callback> parseCallback(String taskId, String sign, String param) {
            if (!"GOOD".equals(sign)) {
                return Optional.empty();
            }
            String[] f = param.split("\\|", -1);
            return Optional.of(new Callback(taskId, null, Integer.parseInt(f[0]),
                    f[1].isEmpty() ? null : f[1], null, f[2].isEmpty() ? null : Long.parseLong(f[2]),
                    null, "王师傅", "13800000000", "msg"));
        }
    }

    @Autowired private ExpressPickupService service;
    @Autowired private PlatformConfigService platformConfig;
    @Autowired private StoreShipSettingService shipSetting;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        ENABLED.set(true);
        LAST_SANDBOX.set(null);
        LAST_CREATE.set(null);
        // 独立内存库（expresspickup profile），拨开关不影响别的测试类；每条用例从「关」开始
        platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, false, 0, "TEST");
        cleanup();
        jdbc.update("insert into mch_store(entity_no, store_no, name, address, is_default, created_at, updated_at)"
                + " values (?, ?, '快递测试店', '山西省运城市盐湖区解放路 1 号', 1, now(), now())", ENTITY, STORE);
        jdbc.update("insert into mch_account(mch_account_no, entity_no, is_owner, login_phone, created_at, updated_at)"
                + " values ('MA-EXP-T', ?, 1, '13900000001', now(), now())", ENTITY);
        jdbc.update("insert into ord_sub_order(sub_order_no, order_no, user_no, entity_no, store_no, fulfillment, status,"
                + " receiver_name, receiver_phone, receiver_address, created_at, updated_at)"
                + " values (?, ?, 'U-EXP-T', ?, ?, 'EXPRESS', 'WAIT_FULFILL', '张三', '13700000000',"
                + " '广东省深圳市南山区科技园 8 号', now(), now())", SUB, ORDER, ENTITY, STORE);
        jdbc.update("insert into ord_item(sub_order_no, order_no, goods_no, sku_no, title, created_at, updated_at)"
                + " values (?, ?, 'G-EXP', 'SK-EXP', '五得利五星特精小麦粉 25kg 家用通用面粉', now(), now())", SUB, ORDER);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from ord_express_pickup where entity_no=?", ENTITY);
        jdbc.update("delete from ord_status_log where sub_order_no=?", SUB);
        jdbc.update("delete from ord_item where sub_order_no=?", SUB);
        jdbc.update("delete from ord_sub_order where sub_order_no=?", SUB);
        jdbc.update("delete from mch_debt_txn where entity_no=?", ENTITY);
        jdbc.update("delete from mch_debt where entity_no=?", ENTITY);
        jdbc.update("delete from mch_account where entity_no=?", ENTITY);
        jdbc.update("delete from mch_store where entity_no=?", ENTITY);
    }

    @Test
    @DisplayName("★★ AC1 报价按价升序；查不到价的那家不出现")
    void quotesSortedAndSkipUnquoted() {
        var qs = service.quotes(ENTITY, null, SUB, new BigDecimal("2.5"));
        assertThat(qs).extracting(ExpressPickupService.QuoteVO::carrier).containsExactly("YTO", "ZTO");
        assertThat(qs.get(0).carrierName()).isEqualTo("圆通速递");
    }

    @Test
    @DisplayName("★★★ AC2–AC5 下单 → 接单 → 取件：运单号回填到子单、运费记欠款；重复推送不多记")
    void pickupShipsOrderAndBooksFreight() {
        PickupVO p = service.create(ENTITY, null, SUB, "ZTO", new BigDecimal("2.5"));
        assertThat(p.status()).isEqualTo(OrdExpressPickup.CREATED);
        assertThat(p.weightG()).isEqualTo(2500);
        String task = "T-" + p.pickupNo();

        assertThatThrownBy(() -> service.create(ENTITY, null, SUB, "YTO", BigDecimal.ONE))
                .as("同一子单同时只能有一张进行中的取件单")
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.EXPRESS_PICKUP_EXISTS);

        // 验签不过：什么都不落
        assertThat(service.onCallback(task, "BAD", "10|ZT001|510")).isFalse();
        assertThat(subStatus()).isEqualTo("WAIT_FULFILL");

        assertThat(service.onCallback(task, "GOOD", "1||")).isTrue();
        assertThat(service.latest(ENTITY, null, SUB).status()).isEqualTo(OrdExpressPickup.ACCEPTED);
        assertThat(subStatus()).as("接单还不是发货：取件前还可能取消").isEqualTo("WAIT_FULFILL");

        assertThat(service.onCallback(task, "GOOD", "10|ZT001|510")).isTrue();
        assertThat(subStatus()).isEqualTo("FULFILLING");
        Map<String, Object> sub = jdbc.queryForMap(
                "select express_no, express_company from ord_sub_order where sub_order_no=?", SUB);
        assertThat(sub.get("express_no")).isEqualTo("ZT001");
        assertThat(sub.get("express_company")).as("存微信码，与手填发货同一口径").isEqualTo("ZTO");
        assertThat(debt()).isEqualTo(510L);

        // 重复推送同一个数：不多记
        service.onCallback(task, "GOOD", "10|ZT001|510");
        assertThat(debt()).isEqualTo(510L);

        // 改重：运费涨到 6 元，补记差额 90 分
        service.onCallback(task, "GOOD", "155|ZT001|600");
        assertThat(debt()).isEqualTo(600L);
        assertThat(jdbc.queryForObject("select count(*) from mch_debt_txn where entity_no=? and source_type='EXPRESS'",
                Integer.class, ENTITY)).isEqualTo(2);

        // 晚到的旧推送不回退
        service.onCallback(task, "GOOD", "1||");
        assertThat(service.latest(ENTITY, null, SUB).status()).isEqualTo(OrdExpressPickup.PICKED);

        assertThatThrownBy(() -> service.cancel(ENTITY, null, SUB))
                .as("已取件不能取消")
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.EXPRESS_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("★★ AC6 取件前可取消；取消后能重新叫")
    void cancelThenRebook() {
        service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE);
        assertThat(service.cancel(ENTITY, null, SUB).status()).isEqualTo(OrdExpressPickup.CANCELLED);
        PickupVO again = service.create(ENTITY, null, SUB, "YTO", BigDecimal.ONE);
        assertThat(again.status()).isEqualTo(OrdExpressPickup.CREATED);
        assertThat(again.carrier()).isEqualTo("YTO");
    }

    @Test
    @DisplayName("★★ AC7 通道没开：直说没开通，不落任何取件单")
    void channelOffSaysSo() {
        ENABLED.set(false);
        assertThatThrownBy(() -> service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.EXPRESS_CHANNEL_OFF);
        assertThat(jdbc.queryForObject("select count(*) from ord_express_pickup where entity_no=?",
                Integer.class, ENTITY)).isZero();
    }

    @Test
    @DisplayName("★ AC2 门店没填地址：说清是寄件信息不全")
    void senderIncomplete() {
        jdbc.update("update mch_store set address=null where store_no=?", STORE);
        assertThatThrownBy(() -> service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.EXPRESS_SENDER_INCOMPLETE);
    }

    @Test
    @DisplayName("★ AC2 别家的单、非快递单、重量越界都拒")
    void guards() {
        assertThatThrownBy(() -> service.create("M-OTHER", null, SUB, "ZTO", BigDecimal.ONE))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.create(ENTITY, null, SUB, "ZTO", new BigDecimal("31")))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        jdbc.update("update ord_sub_order set fulfillment='STORE_PICKUP' where sub_order_no=?", SUB);
        assertThatThrownBy(() -> service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    @Test
    @DisplayName("★★★ §7 AC9/AC11 快递测试模式：下单、查价、取消都走测试环境；取件后照常回填运单号，但运费不记欠款")
    void testModeGoesSandboxAndBooksNoDebt() {
        platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, true, 0, "TEST");
        service.quotes(ENTITY, null, SUB, BigDecimal.ONE);
        assertThat(LAST_SANDBOX.get()).as("查价走测试环境").isTrue();
        PickupVO p = service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE);
        assertThat(p.sandbox()).isTrue();
        assertThat(LAST_CREATE.get().sandbox()).isTrue();

        // 开关中途关掉：这一单仍按它下单时的环境走（取消要回同一个环境）
        platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, false, 0, "TEST");
        String task = "T-" + p.pickupNo();
        service.onCallback(task, "GOOD", "10|ZT-TEST-1|510");
        assertThat(subStatus()).as("测试单同样走完发货链路 —— 这正是要测的闭环").isEqualTo("FULFILLING");
        assertThat(debt()).as("测试环境的运费是假的，平台也没付 —— 不能记成商家欠款").isZero();
    }

    @Test
    @DisplayName("★★ §7 AC9 开关关着：走正式环境；取消回到下单时的环境")
    void prodModeGoesProd() {
        PickupVO p = service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE);
        assertThat(p.sandbox()).isFalse();
        assertThat(LAST_CREATE.get().sandbox()).isFalse();
        platformConfig.saveFeatureFlag(PayModes.EXPRESS_TEST_MODE_FLAG, true, 0, "TEST");
        service.cancel(ENTITY, null, SUB);
        assertThat(LAST_SANDBOX.get()).as("开关后来拨开了，这一单的取消仍回正式环境").isFalse();
    }

    @Test
    @DisplayName("★★★ §7 AC12 发货设置覆盖寄件人；清空即回落门店名 / 店主手机 / 门店地址")
    void shipSettingOverridesSender() {
        shipSetting.save(ENTITY, STORE, new StoreShipSettingService.ShipSettingCmd(
                "虹选粮油仓", "0359-1234567", "山西省运城市盐湖区仓储路 8 号", "YTO", 5000));
        service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE);
        var sender = LAST_CREATE.get().sender();
        assertThat(sender.name()).isEqualTo("虹选粮油仓");
        assertThat(sender.mobile()).isEqualTo("0359-1234567");
        assertThat(sender.address()).isEqualTo("山西省运城市盐湖区仓储路 8 号");

        var view = shipSetting.get(ENTITY, STORE);
        assertThat(view.carrier()).isEqualTo("YTO");
        assertThat(view.weightG()).isEqualTo(5000);
        assertThat(view.defaultSenderName()).as("placeholder 显示不填时会用的").isEqualTo("快递测试店");
        assertThat(view.defaultSenderPhone()).isEqualTo("13900000001");

        service.cancel(ENTITY, null, SUB);
        shipSetting.save(ENTITY, STORE, new StoreShipSettingService.ShipSettingCmd("", " ", null, null, null));
        service.create(ENTITY, null, SUB, "ZTO", BigDecimal.ONE);
        sender = LAST_CREATE.get().sender();
        assertThat(sender.name()).isEqualTo("快递测试店");
        assertThat(sender.mobile()).isEqualTo("13900000001");
        assertThat(sender.address()).isEqualTo("山西省运城市盐湖区解放路 1 号");
    }

    @Test
    @DisplayName("★ §7 AC12 发货设置的校验：电话格式、快递公司码、重量区间")
    void shipSettingValidates() {
        for (var bad : List.of(
                new StoreShipSettingService.ShipSettingCmd(null, "abc", null, null, null),
                new StoreShipSettingService.ShipSettingCmd(null, null, null, "NOPE", null),
                new StoreShipSettingService.ShipSettingCmd(null, null, null, null, 50_000))) {
            assertThatThrownBy(() -> shipSetting.save(ENTITY, STORE, bad))
                    .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        }
        assertThatThrownBy(() -> shipSetting.get("M-OTHER", STORE))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    private String subStatus() {
        return jdbc.queryForObject("select status from ord_sub_order where sub_order_no=?", String.class, SUB);
    }

    private long debt() {
        Long v = jdbc.query("select balance_minor from mch_debt where entity_no=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, ENTITY);
        return v == null ? 0L : v;
    }
}
