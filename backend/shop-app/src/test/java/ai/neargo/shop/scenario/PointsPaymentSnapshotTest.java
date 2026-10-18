package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:points-payment-proof;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PointsPaymentSnapshotTest {
    @Autowired PaymentLedgerService ledger;
    @Autowired PointsPaymentSnapshotPort proofs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void only_success_with_exact_request_amount_is_proof() {
        var cmd = request();
        String out = ledger.open(cmd);
        assertThat(proofs.verified(cmd.orderNo())).isEmpty();
        ledger.settle(new SettlePort.PaymentSettled(out, "TEST", "trusted-" + out, 1234, 123456L));
        var proof = proofs.verified(cmd.orderNo()).orElseThrow();
        assertThat(proof.userNo()).isEqualTo(cmd.userNo());
        assertThat(proof.currency()).isEqualTo("KWD");
        assertThat(proof.forSubOrder(cmd.stores().getFirst().subOrderNo()).tradeNo())
                .isEqualTo("trusted-" + out);
        assertThatThrownBy(() -> proof.forSubOrder("foreign-sub"))
                .hasMessageContaining("POINTS_PAYMENT_STORE_MISSING");
    }

    @Test
    void mismatched_verified_cash_or_missing_original_request_fails_closed() {
        var cmd = request();
        String out = ledger.open(cmd);
        ledger.settle(new SettlePort.PaymentSettled(out, "TEST", "trusted-" + out, 1235, 123456L));
        assertThatThrownBy(() -> proofs.verified(cmd.orderNo()))
                .hasMessageContaining("POINTS_PAYMENT_AMOUNT_MISMATCH");
        jdbc.update("UPDATE stl_payment SET payment_request_json=NULL WHERE out_trade_no=?", out);
        assertThatThrownBy(() -> proofs.verified(cmd.orderNo()))
                .hasMessageContaining("POINTS_PAYMENT_REQUEST_MISSING");
    }

    @Test
    void zero_cash_keeps_the_original_store_route_while_free_is_the_actual_cash_channel() {
        var original = request();
        var cmd =
                new PaymentOpen(
                        original.orderNo(),
                        original.userNo(),
                        null,
                        "FREE",
                        0,
                        original.subject(),
                        original.currency(),
                        original.stores());
        String out = ledger.open(cmd);
        ledger.settle(new SettlePort.PaymentSettled(out, "FREE", "FREE:" + out, 0, 123456L));
        var proof = proofs.verified(cmd.orderNo()).orElseThrow();
        assertThat(proof.channel()).isEqualTo("FREE");
        assertThat(proof.forSubOrder(cmd.stores().getFirst().subOrderNo()).channel())
                .isEqualTo("TEST");
        assertThat(proof.amountMinor()).isZero();
    }

    private PaymentOpen request() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var route = new PaymentRoute("owner", "store", "pay", "TEST", "sub-mch", "pay:7", "DIRECT");
        return new PaymentOpen(
                "proof-" + id,
                "user-" + id,
                null,
                "TEST",
                1234,
                "synthetic",
                "KWD",
                List.of(new PaymentStore("sub-" + id, route)));
    }
}
