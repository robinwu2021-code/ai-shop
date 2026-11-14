package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayQrCodeMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayApiNonceMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppGrantMapper;
import ai.neargo.shop.pay.openapp.entity.PayApiNonce;
import ai.neargo.shop.pay.openapp.entity.PayAppGrant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

/**
 * 迁移 11（TDD-支付服务-I5接入层 S1）：接入层依赖的三条唯一性 —— nonce 重放即撞键（无 Redis 时的防重放）、
 * 同一接入方对同一门店只有一条授权、静态码码值唯一。
 */
// 支付服务 I1–I5 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOpenAccessSchemaTest {

    @Autowired private PayApiNonceMapper nonces;
    @Autowired private PayAppGrantMapper grants;
    @Autowired private PayQrCodeMapper qrCodes;

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("★★★ 同一调用方同一 nonce 只能落一次 —— 第二次就是重放")
    void nonceReplayCollides() {
        String n = UUID.randomUUID().toString();
        nonces.insert(nonce(n));
        assertThatThrownBy(() -> nonces.insert(nonce(n))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("★★★ 同一接入方对同一门店只有一条授权")
    void oneGrantPerAppAndStore() {
        long store = System.nanoTime() % 1_000_000_000L;
        grants.insert(grant(77L, store));
        assertThatThrownBy(() -> grants.insert(grant(77L, store))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("★★ 静态码码值唯一")
    void qrCodeUnique() {
        String code = UUID.randomUUID().toString().replace("-", "");
        qrCodes.insert(qr(code));
        assertThatThrownBy(() -> qrCodes.insert(qr(code))).isInstanceOf(DuplicateKeyException.class);
        assertThat(qrCodes.selectCount(null)).isPositive();
    }

    private static PayApiNonce nonce(String n) {
        PayApiNonce x = new PayApiNonce();
        x.setCallerType("APP");
        x.setCallerId(1L);
        x.setNonce(n);
        x.setExpireAt(now().plusMinutes(5));
        return x;
    }

    private static PayAppGrant grant(Long app, Long store) {
        PayAppGrant g = new PayAppGrant();
        g.setPayAppId(app);
        g.setStoreId(store);
        g.setActions("COLLECT,READ");
        g.setStatus("ACTIVE");
        g.setGrantedBy("U-1");
        g.setGrantedAt(now());
        return g;
    }

    private static PayQrCode qr(String code) {
        PayQrCode q = new PayQrCode();
        q.setQrCode(code);
        q.setStoreId(1L);
        q.setKind("STORE");
        q.setCurrency("CNY");
        q.setSingleLimitMinor(100_000L);
        q.setDailyLimitMinor(1_000_000L);
        q.setStatus("ACTIVE");
        q.setCreatedByType("BIZ");
        return q;
    }
}
