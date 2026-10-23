package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.PayGateway.PrepayCommand;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Persist requests before sending; configuration changes cannot rewrite an existing payment attempt. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payment-request-snapshot;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PaymentRequestSnapshotTest {
    @Autowired private SettlePort settle;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrdinaryPaymentPort ordinary;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TestPayGateway bank;

    @Test
    void request_is_persisted_before_dispatch_and_gateway_uses_actual_currency_and_direct_account() {
        var cmd = command();
        var result = settle.initPayment(cmd);
        assertThat(result.success()).isTrue();
        var snapshot = ledger.paymentRequest(result.outTradeNo());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow()).isEqualTo(cmd);
        var sent = ArgumentCaptor.forClass(PrepayCommand.class);
        verify(bank).prepay(sent.capture());
        assertThat(sent.getValue().currency()).isEqualTo("KWD");
        assertThat(sent.getValue().subMchId()).isEqualTo("synthetic-channel-account");
        assertThat(jdbc.queryForObject("SELECT currency FROM stl_payment WHERE out_trade_no=?",
                String.class, result.outTradeNo())).isEqualTo("KWD");
    }

    @Test
    void retry_reuses_original_store_and_account_even_if_the_caller_has_new_configuration() {
        var cmd = command();
        var first = settle.initPayment(cmd);
        var changed = new PaymentOpen(cmd.orderNo(), cmd.userNo(), null, "TEST", 1234,
                "changed description", "KWD", List.of(new PaymentStore("another-sub",
                        new PaymentRoute("another-entity", "another-store", "another-pay", "TEST",
                                "another-channel-account", "another-pay:99", "DIRECT"))));
        var second = settle.initPayment(changed);
        assertThat(second.outTradeNo()).isEqualTo(first.outTradeNo());
        var sent = ArgumentCaptor.forClass(PrepayCommand.class);
        verify(bank, times(2)).prepay(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(request -> {
            assertThat(request.subMchId()).isEqualTo("synthetic-channel-account");
            assertThat(request.subject()).isEqualTo("synthetic goods");
        });
        assertThat(ledger.paymentRequest(first.outTradeNo()).orElseThrow().stores())
                .containsExactlyElementsOf(cmd.stores());
    }

    @Test
    void changed_user_amount_currency_or_channel_cannot_reuse_a_pending_attempt() {
        var cmd = command();
        ledger.open(cmd);
        for (var changed : List.of(
                new PaymentOpen(cmd.orderNo(), "foreign-user", null, "TEST", 1234, "x", "KWD", cmd.stores()),
                new PaymentOpen(cmd.orderNo(), cmd.userNo(), null, "TEST", 1235, "x", "KWD", cmd.stores()),
                new PaymentOpen(cmd.orderNo(), cmd.userNo(), null, "TEST", 1234, "x", "CNY", cmd.stores()),
                new PaymentOpen(cmd.orderNo(), cmd.userNo(), null, "WECHAT", 1234, "x", "KWD", List.of()))) {
            assertThatThrownBy(() -> ledger.open(changed)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                Long.class, cmd.orderNo())).isEqualTo(1);
    }

    @Test
    void mixed_direct_accounts_and_foreign_channels_cannot_create_an_unrouteable_payment() {
        var cmd = command();
        var other = new PaymentStore("other-sub", new PaymentRoute("other-owner", "other-store", "other-pay",
                "TEST", "other-sub-mch", "other-pay:8", "DIRECT"));
        assertThatThrownBy(() -> settle.initPayment(new PaymentOpen(cmd.orderNo(), cmd.userNo(), null,
                "TEST", 1234, "x", "KWD", List.of(cmd.stores().getFirst(), other))))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongChannel = new PaymentStore("other-sub", new PaymentRoute("owner", "store", "pay",
                "WECHAT", "channel-sub", "pay:8", "DIRECT"));
        assertThatThrownBy(() -> ledger.open(new PaymentOpen(cmd.orderNo(), cmd.userNo(), null,
                "TEST", 1234, "x", "KWD", List.of(wrongChannel))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                Long.class, cmd.orderNo())).isZero();
    }

    @Test
    void an_old_row_without_snapshot_never_becomes_an_authoritative_source_snapshot() {
        var cmd = command();
        String out = ledger.open(cmd);
        // Simulate an unsupported historical source; only the request snapshot is deliberately removed.
        jdbc.update("UPDATE stl_payment SET payment_request_json=NULL WHERE out_trade_no=?", out);
        assertThat(ledger.paymentRequest(out)).isEmpty();
        assertThatThrownBy(() -> settle.initPayment(cmd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordinary_ledger_port_tracks_exact_retry_owner_and_success_without_channel_initiation() {
        var cmd = command();
        var first = ordinary.open(cmd);
        assertThat(first.outTradeNo()).isEqualTo(cmd.orderNo());
        assertThat(first.userNo()).isEqualTo(cmd.userNo());
        assertThat(first.currency()).isEqualTo("KWD");
        assertThat(first.amountMinor()).isEqualTo(1234L);
        assertThat(first.status()).isEqualTo("PENDING");
        ledger.close(first.outTradeNo(), "verified pre-dispatch abandonment");
        var second = ordinary.open(cmd);
        assertThat(second.outTradeNo()).isEqualTo(cmd.orderNo() + "-2");
        assertThat(ordinary.query(new OrdinaryPaymentPort.Reference(cmd.orderNo(), cmd.userNo(),
                first.outTradeNo())).payment().status()).isEqualTo("CLOSED");
        assertThatThrownBy(() -> ordinary.query(new OrdinaryPaymentPort.Reference("foreign-order",
                cmd.userNo(), second.outTradeNo()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ordinary.query(new OrdinaryPaymentPort.Reference(cmd.orderNo(),
                "foreign-user", second.outTradeNo()))).isInstanceOf(IllegalArgumentException.class);
        ledger.settle(new SettlePort.PaymentSettled(second.outTradeNo(), cmd.payChannel(),
                "ordinary-proof", 1234, 123456L, "KWD"));
        var paid = ordinary.success(new OrdinaryPaymentPort.OrderReference(cmd.orderNo(), cmd.userNo()));
        assertThat(paid.payment().outTradeNo()).isEqualTo(second.outTradeNo());
        assertThat(paid.payment().status()).isEqualTo("SUCCESS");
        assertThat(paid.payment().currency()).isEqualTo("KWD");
    }

    @Test
    void free_channel_keeps_frozen_non_default_currency_when_it_settles_inline() {
        var source = command();
        var free = new PaymentOpen(source.orderNo(), source.userNo(), source.entityNo(), "FREE", 0,
                source.subject(), "KWD", source.stores());
        var result = settle.initPayment(free);
        assertThat(result.success()).isTrue();
        assertThat(ledger.paymentByOutTradeNo(result.outTradeNo()).status()).isEqualTo("SUCCESS");
        assertThat(ledger.paymentByOutTradeNo(result.outTradeNo()).currency()).isEqualTo("KWD");
    }

    @Test
    void payer_identity_freezes_on_exact_attempt_before_send_and_conflicts_preserve_pending() {
        var source = command();
        var request = new PaymentOpen(source.orderNo(), source.userNo(), source.entityNo(), "WECHAT", 1234,
                source.subject(), "KWD", List.of());
        String out = ledger.open(request);
        ledger.freezePayer(out, request.userNo(), "original-openid", "original-app");
        ledger.freezePayer(out, request.userNo(), "original-openid", "original-app");
        assertThatThrownBy(() -> ledger.freezePayer(out, request.userNo(), "changed-openid", "original-app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.freezePayer(out, "other-owner", "original-openid", "original-app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.paymentByOutTradeNo(out).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payer_openid FROM stl_payment WHERE out_trade_no=?",
                String.class, out)).isEqualTo("original-openid");
    }

    private PaymentOpen command() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        var route = new PaymentRoute("synthetic-owner", "synthetic-store", "synthetic-pay", "TEST",
                "synthetic-channel-account", "synthetic-pay:7", "DIRECT");
        return new PaymentOpen("snapshot-" + id, "user-" + id, null, "TEST", 1234,
                "synthetic goods", "KWD", List.of(new PaymentStore("sub-" + id, route)));
    }
}
