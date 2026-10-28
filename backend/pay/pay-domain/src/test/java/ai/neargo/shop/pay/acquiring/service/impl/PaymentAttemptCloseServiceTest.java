package ai.neargo.shop.pay.acquiring.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.platform.InProcessChannel;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.pay.PayInstruction;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 冻结路由和渠道关闭证明是实际关单的边界；超时与不支持不能被当作成功。 */
class PaymentAttemptCloseServiceTest {
    private final PayMerchantQueryPort merchants = mock(PayMerchantQueryPort.class);
    private final Peer peer = new Peer();
    private final PayOrder order = new PayOrder();
    private final StlPayment attempt = new StlPayment();
    private final PayCaller caller = new PayCaller(CallerType.INTERNAL, 1L, Set.of(7L), "OP-1", null, null);
    private final ChannelProductCatalog catalog = new ChannelProductCatalog() {
        @Override public Set<ChannelKey> enabledPayKeys() { return Set.of(); }
        @Override public boolean payEnabled(ChannelKey key) { return true; }
    };
    private final PaymentAttemptCloseServiceImpl service = new PaymentAttemptCloseServiceImpl(peer,
            new ChannelContextFactory(new ChannelCapabilityRegistry(List.of(new InProcessChannel(new TestPayGateway()))),
                    catalog), merchants);

    @BeforeEach
    void setUp() {
        order.setId(11L);
        order.setBizType("PARTNER");
        order.setBizNo("BIZ-1");
        attempt.setId(22L);
        attempt.setPayChannel("TEST");
        attempt.setOutTradeNo("T-22");
        attempt.setCurrency("CNY");
        attempt.setMarket("CN");
        attempt.setPayMerchantId(33L);
        attempt.setPayMerchantNo("ORIGINAL-MERCHANT");
        attempt.setSubMerchantId("ORIGINAL-SUB");
        attempt.setSecKeyVersion(3);
        when(merchants.routeByPayMerchantId(33L, 3)).thenReturn(Optional.of(new PayMerchantQueryPort.PayRoute(
                99L, 33L, "CURRENT-MERCHANT", "TEST", "CN", "CNY", "PLATFORM_SUB", "AGGREGATED",
                "MCH", "CURRENT-SUB", null, 9, 0)));
    }

    @Test
    void closesUsingOriginalMerchantSubMerchantAndKeyVersion() {
        peer.closeResult = new ChannelResult.Succeeded(null, null, null, Map.of());
        assertThat(service.close(caller, order, attempt)).isTrue();
        assertThat(peer.accounts).hasSize(1);
        var account = peer.accounts.getFirst();
        assertThat(account.payMerchantId()).isEqualTo(33L);
        assertThat(account.payMerchantNo()).isEqualTo("ORIGINAL-MERCHANT");
        assertThat(account.subMerchantId()).isEqualTo("ORIGINAL-SUB");
        assertThat(account.keyVersion()).isEqualTo(3);
        CloseCommand sent = (CloseCommand) peer.commands.getFirst();
        assertThat(sent.outTradeNo()).isEqualTo("T-22");
        assertThat(sent.meta().payOrderId()).isEqualTo(11L);
        assertThat(sent.meta().stlPaymentId()).isEqualTo(22L);
        assertThat(sent.meta().source().operatorNo()).isEqualTo("OP-1");
    }

    @Test
    void timeoutWithUnpaidQueryIsNotClosed() {
        assertThat(service.close(caller, order, attempt)).isFalse();
        assertThat(peer.commands).hasSize(2);
    }

    @Test
    void lostCloseReceiptCanBeRecoveredFromClosedChannelState() {
        peer.queryResult = new ChannelResult.Pending(PayInstruction.none(), null, "CLOSED");
        assertThat(service.close(caller, order, attempt)).isTrue();
        assertThat(peer.accounts).hasSize(2);
        assertThat(peer.accounts.get(1)).isEqualTo(peer.accounts.getFirst());
    }

    @Test
    void unsupportedCloseDoesNotSucceedWithoutProof() {
        peer.closeResult = new ChannelResult.Failed(PayErrorCode.CHANNEL_UNSUPPORTED, null, "unsupported", false);
        assertThat(service.close(caller, order, attempt)).isFalse();
    }

    @Test
    void paymentWonTheRaceIsNotAClosedReceipt() {
        peer.queryResult = new ChannelResult.Succeeded("PAID-TX", new Money(100, "CNY"), null, Map.of());
        assertThat(service.close(caller, order, attempt)).isFalse();
    }

    @Test
    void missingOriginalMerchantDoesNotSelectTodaysStoreBinding() {
        when(merchants.routeByPayMerchantId(33L, 3)).thenReturn(Optional.empty());
        assertThat(service.close(caller, order, attempt)).isFalse();
        assertThat(peer.commands).isEmpty();
    }

    @Test
    void notFoundAfterCloseTimeoutCannotProveAnInflightCreateWillNotArrive() {
        peer.queryResult = new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "missing", false);
        assertThat(service.close(caller, order, attempt)).isFalse();
    }

    @Test
    void initialQueryAlsoUsesOriginalIdentity() {
        service.query(caller, order, attempt);
        assertThat(peer.accounts).hasSize(1);
        assertThat(peer.accounts.getFirst().subMerchantId()).isEqualTo("ORIGINAL-SUB");
        assertThat(peer.accounts.getFirst().keyVersion()).isEqualTo(3);
    }

    private static final class Peer implements ChannelGateway {
        private final List<ChannelAccountContext> accounts = new ArrayList<>();
        private final List<ChannelCommand> commands = new ArrayList<>();
        private ChannelResult closeResult = new ChannelResult.Unknown("TIMEOUT");
        private ChannelResult queryResult = new ChannelResult.Pending(PayInstruction.none(), null, "NOTPAY");
        @Override public ChannelResult execute(ChannelAccountContext account, ChannelCommand command) {
            accounts.add(account);
            commands.add(command);
            return command instanceof CloseCommand ? closeResult : queryResult;
        }
    }
}
