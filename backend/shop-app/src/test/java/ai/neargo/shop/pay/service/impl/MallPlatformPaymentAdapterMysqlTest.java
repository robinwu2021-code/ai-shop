package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.commercebridge.*;
import ai.neargo.shop.pay.channel.PayGateway;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class MallPlatformPaymentAdapterMysqlTest {
    @Test
    void typedFrozenPayerBindingFeedsExistingLedgerAndMissingBindingNeverCreatesCashIntent()
            throws Exception {
        try (var f = new PlatformCollectionMysqlTest.Fixture()) {
            var raw = new RawPrincipalIdentity("MERCHANT", "USR", "raw-user");
            var command =
                    new PaymentIntentCommand(
                            "MAIN",
                            "order",
                            "platform-mch",
                            new Money("CNY", 100),
                            Instant.ofEpochMilli(20000),
                            "A",
                            "USR:HtM9BphH_zJLOHQGYW0gtAo3Tpjr_BCcW5Bi2WpIqSg",
                            "WECHAT",
                            "JSAPI",
                            "binding:1",
                            Instant.ofEpochMilli(10250),
                            raw);
            var unavailable =
                    new ShopMallPlatformPaymentAdapter(
                            f.service, null, ShopMallPayerBindings.UNAVAILABLE);
            assertEquals(
                    Optional.of("PAYER_BINDING_UNPROVEN"), unavailable.create(command).errorCode());
            assertEquals(
                    0, f.jdbc.queryForObject("SELECT count(*) FROM stl_payment", Integer.class));
            ShopMallPayerBindings bindings =
                    new ShopMallPayerBindings() {
                        public boolean available() {
                            return true;
                        }

                        public Optional<Verified> current(ActorContext a, String c, String m) {
                            return Optional.empty();
                        }

                        public Optional<Verified> frozen(
                                RawPrincipalIdentity identity,
                                String buyer,
                                String channel,
                                String method,
                                String reference) {
                            if (!identity.equals(raw) || !reference.equals("binding:1"))
                                return Optional.empty();
                            return Optional.of(
                                    new Verified(reference, raw, "wx-app", "verified-openid"));
                        }
                    };
            var adapter = new ShopMallPlatformPaymentAdapter(f.service, null, bindings);
            var pending = adapter.create(command);
            assertEquals(AsyncStatus.PENDING, pending.status());
            String external =
                    f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX1",
                            "CNY",
                            10000L,
                            "platform-mch",
                            external,
                            "SUCCESS",
                            1000L));
            var paid = adapter.query(new PaymentQuery("MAIN", "order", "A", command));
            assertEquals(AsyncStatus.SUCCEEDED, paid.status());
            assertEquals(Optional.of(Instant.ofEpochMilli(10000)), paid.paidAt());
            assertEquals(1000L, paid.paidAtPrecisionMs());
            assertEquals(pending.paymentNo(), paid.paymentNo());
            var wrong =
                    new PaymentIntentCommand(
                            "MAIN",
                            "order",
                            "platform-mch",
                            command.amount(),
                            command.expiresAt(),
                            "B",
                            "MCH:HtM9BphH_zJLOHQGYW0gtAo3Tpjr_BCcW5Bi2WpIqSg",
                            "WECHAT",
                            "JSAPI",
                            "binding:1",
                            command.preparedAt(),
                            raw);
            assertEquals(Optional.of("PAYER_BINDING_UNPROVEN"), adapter.create(wrong).errorCode());
            assertEquals(
                    1, f.jdbc.queryForObject("SELECT count(*) FROM stl_payment", Integer.class));
        }
    }
}
