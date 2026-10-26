package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC2 / AC3：收款路由只按门店绑定解析、没有兜底；绑定按同主体、同市场、ACTIVE 校验（TDD-支付服务-I4进件与收款商户 S2）。
 *
 * <p>此前解析是「门店绑定 → 门店自己的号 → 默认门店 → 主体级号」一路兜下去：门店没配也能收款，收进了主体某个号 ——
 * 多主体 / 多门店时就是 R01、R02 那类「钱进错户」。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayMerchantRoutingFlowTest {

    @Autowired private PayMerchantQueryPort routes;
    @Autowired private PayMerchantBindingService bindings;
    @Autowired private JdbcTemplate jdbc;

    private Long storeA;
    private Long storeB;
    private Long foreignStore;
    private Long twStore;
    private Long merchant;

    private String entity(String market) {
        String e = "E-RT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                e, "主体 " + e, market);
        return e;
    }

    private Long store(String entityNo) {
        String s = "S-RT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                entityNo, s, "门店 " + s);
        return jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, s);
    }

    private Long payMerchant(Long openingStore, String status, int priority) {
        String no = "PM-RT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO pay_merchant (pay_merchant_no, owner_type, store_id, pay_channel, market, currency,"
                + " access_mode, funds_path, channel_merchant_id, sub_merchant_id, parent_pay_merchant_id, sec_key_version,"
                + " status, invoice_capable, priority, created_at, updated_at) VALUES (?, 'STORE', ?, 'WECHAT', 'CN', 'CNY',"
                + " 'MERCHANT_OWN', 'DIRECT', ?, '', NULL, 0, ?, 0, ?, NOW(), NOW())",
                no, openingStore, "MCH-" + no, status, priority);
        return jdbc.queryForObject("SELECT id FROM pay_merchant WHERE pay_merchant_no=?", Long.class, no);
    }

    @BeforeEach
    void world() {
        String e = entity("CN");
        storeA = store(e);
        storeB = store(e);
        foreignStore = store(entity("CN"));
        twStore = store(entity("TW"));
        merchant = payMerchant(storeA, "ACTIVE", 100);
    }

    @Test
    @DisplayName("★★★ 只按门店绑定：A 店绑了就解析到；同主体的 B 店没绑就是不可收款 —— 不取「主体的第一个可用号」（R01）")
    void onlyStoreBindingResolves() {
        bindings.bind(storeA, "WECHAT", merchant, "OPS-1");
        PayRoute r = routes.routeOf(storeA, "WECHAT").orElseThrow();
        assertThat(r.payMerchantId()).isEqualTo(merchant);
        assertThat(r.fundsPath()).isEqualTo("DIRECT");
        assertThat(r.accessMode()).isEqualTo("MERCHANT_OWN");
        assertThat(routes.routeOf(storeB, "WECHAT")).as("同主体别的门店没绑定 → 没有路由").isEmpty();
        assertThat(routes.routeOf(storeA, "ALIPAY")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 收款商户不是 ACTIVE（暂停）→ 新收款解析不到；退款 / 查单按冻结的 id 仍能解析")
    void suspendedMerchantNotCollectableButRefundable() {
        bindings.bind(storeA, "WECHAT", merchant, "OPS-1");
        jdbc.update("UPDATE pay_merchant SET status='SUSPENDED' WHERE id=?", merchant);
        assertThat(routes.routeOf(storeA, "WECHAT")).isEmpty();
        assertThat(routes.collectableRoutes(storeA, "CN")).isEmpty();
        assertThat(routes.routeByPayMerchantId(merchant, 0)).isPresent();
    }

    @Test
    @DisplayName("★★ 可收款路由按市场过滤、按优先级排序")
    void collectableRoutesByPriority() {
        Long alipay = payMerchant(storeA, "ACTIVE", 10);
        jdbc.update("UPDATE pay_merchant SET pay_channel='ALIPAY' WHERE id=?", alipay);
        bindings.bind(storeA, "WECHAT", merchant, "OPS-1");
        bindings.bind(storeA, "ALIPAY", alipay, "OPS-1");
        assertThat(routes.collectableRoutes(storeA, "CN")).extracting(PayRoute::payChannel)
                .containsExactly("ALIPAY", "WECHAT");
        assertThat(routes.collectableRoutes(storeA, "TW")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 绑定校验：别的主体的门店、别的市场、非 ACTIVE、渠道不符一律拒（R02 / R03 / R26）")
    void bindingRules() {
        assertThat(code(() -> bindings.bind(foreignStore, "WECHAT", merchant, "OPS-1"))).isEqualTo(ErrorCode.PAY_BINDING_INVALID);
        assertThat(code(() -> bindings.bind(twStore, "WECHAT", merchant, "OPS-1"))).isEqualTo(ErrorCode.PAY_BINDING_INVALID);
        assertThat(code(() -> bindings.bind(storeB, "ALIPAY", merchant, "OPS-1"))).isEqualTo(ErrorCode.PAY_BINDING_INVALID);
        Long onboarding = payMerchant(storeA, "ONBOARDING", 100);
        assertThat(code(() -> bindings.bind(storeB, "WECHAT", onboarding, "OPS-1"))).isEqualTo(ErrorCode.PAY_BINDING_INVALID);
        bindings.bind(storeB, "WECHAT", merchant, "OPS-1");
        assertThat(routes.routeOf(storeB, "WECHAT")).as("同主体的另一家门店可以共用").isPresent();
    }

    @Test
    @DisplayName("★★★ 改绑：同一门店 × 渠道同一时刻只有一条 ACTIVE，旧的那条转 INACTIVE（留痕）")
    void rebindKeepsOneActive() {
        Long other = payMerchant(storeA, "ACTIVE", 100);
        bindings.bind(storeA, "WECHAT", merchant, "OPS-1");
        bindings.bind(storeA, "WECHAT", other, "OPS-2");
        assertThat(routes.routeOf(storeA, "WECHAT").orElseThrow().payMerchantId()).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_merchant_binding WHERE store_id=? AND pay_channel='WECHAT'"
                + " AND status='ACTIVE'", Integer.class, storeA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_merchant_binding WHERE store_id=? AND pay_channel='WECHAT'",
                Integer.class, storeA)).isEqualTo(2);
    }

    private static ErrorCode code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (BizException e) {
            return e.errorCode();
        }
    }

}
