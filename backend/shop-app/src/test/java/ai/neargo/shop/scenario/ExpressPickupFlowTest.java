package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import ai.neargo.shop.trade.entity.OrdExpressPickup;
import ai.neargo.shop.trade.service.ExpressPickupService;
import ai.neargo.shop.trade.service.ExpressPickupService.PickupVO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
        public Optional<Quote> quote(String carrier, String from, String to, int weightG) {
            return switch (carrier) {
                case "ZTO" -> Optional.of(new Quote("ZTO", 900, 1500));
                case "YTO" -> Optional.of(new Quote("YTO", 700, 1500));
                default -> Optional.empty();   // 京东这条线不接 —— 报价单里不该出现它
            };
        }

        @Override
        public Booked create(CreateCmd cmd) {
            return new Booked(true, "T-" + cmd.thirdOrderNo(), "O-" + cmd.thirdOrderNo(), null, "ok");
        }

        @Override
        public Booked cancel(String taskId, String orderId, String reason) {
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
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        ENABLED.set(true);
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

    private String subStatus() {
        return jdbc.queryForObject("select status from ord_sub_order where sub_order_no=?", String.class, SUB);
    }

    private long debt() {
        Long v = jdbc.query("select balance_minor from mch_debt where entity_no=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, ENTITY);
        return v == null ? 0L : v;
    }
}
