package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayFundsOperations;
import ai.neargo.shop.pay.channel.PayFundsOperations.Operation;
import ai.neargo.shop.pay.channel.PayFundsOperations.Receipt;
import ai.neargo.shop.pay.channel.PayFundsOperations.Request;
import ai.neargo.shop.pay.channel.PayFundsOperations.Status;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PointsFundsAdapterTest {
    @Test
    void capability_gates_before_acceptance() {
        var router=mock(PayGatewayRouter.class);
        var master=mock(PayChannelMasterService.class);
        var legacy=mock(PayGateway.class);
        SysPayChannel channel=new SysPayChannel();
        channel.setEnabled(true); channel.setCurrency("CNY");
        when(master.find("TEST")).thenReturn(Optional.of(channel));
        when(master.supportsSubsidy("TEST")).thenReturn(true);
        when(router.supports("TEST")).thenReturn(true);
        when(router.of("TEST")).thenReturn(legacy);
        var capabilities=adapter(router,master).capabilities(context());
        assertThat(capabilities.subsidy()).isFalse();
        assertThat(capabilities.transfer()).isFalse();
        assertThat(capabilities.internalReplenishment()).isFalse();
        assertThat(capabilities.collection()).isFalse();
        verifyNoInteractions(legacy);
    }

    @Test
    void unregistered_channel_rejects_execution_without_default_gateway() {
        var router=mock(PayGatewayRouter.class);
        var master=mock(PayChannelMasterService.class);
        when(master.find("TEST")).thenReturn(Optional.empty());
        var result=adapter(router,master).submit(command());
        assertThat(result.status()).isEqualTo(FundsStatus.FAILED);
        assertThat(result.evidenceNo()).isNull();
        verifyNoInteractions(router);
    }

    @Test
    void missing_query_adapter_is_unknown_and_never_queries_payment() {
        var router=mock(PayGatewayRouter.class);
        var master=mock(PayChannelMasterService.class);
        var legacy=mock(PayGateway.class);
        when(router.supports("TEST")).thenReturn(true);
        when(router.of("TEST")).thenReturn(legacy);
        var result=adapter(router,master).query(command());
        assertThat(result.status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(result.evidenceNo()).isNull();
        verifyNoInteractions(master,legacy);
    }

    @Test
    void completed_receipt_maps_original_request_without_changing_amount_or_recipient() {
        NativeFixture f=nativeFixture();
        Request original=nativeRequest();
        when(f.operations().executeFunds(original)).thenReturn(receipt(Status.SUCCEEDED,"EVIDENCE"));
        var result=adapter(f.router(),f.master()).submit(command());
        assertThat(result.ticketNo()).isEqualTo("TICKET");
        assertThat(result.status()).isEqualTo(FundsStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo(30);
        assertThat(result.evidenceNo()).isEqualTo("EVIDENCE");
        verify(f.operations()).executeFunds(original);
    }

    @Test
    void execution_without_recoverable_query_cannot_accept_new_promise() {
        NativeFixture f=nativeFixture();
        when(f.operations().fundsCapabilities(any())).thenReturn(
                new PayFundsOperations.Capabilities(Set.of(Operation.SUBSIDY),Set.of()));
        PointsFundsGateway adapter=adapter(f.router(),f.master());
        assertThat(adapter.capabilities(context()).subsidy()).isFalse();
        assertThat(adapter.submit(command()).reason()).isEqualTo("POINTS_CAPABILITY_UNSUPPORTED");
        verify(f.operations(),never()).executeFunds(any());
    }

    @Test
    void transport_exception_does_not_become_definitive_failure() {
        NativeFixture f=nativeFixture();
        when(f.operations().executeFunds(any())).thenThrow(new IllegalStateException("test timeout"));
        var result=adapter(f.router(),f.master()).submit(command());
        assertThat(result.status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(result.evidenceNo()).isNull();
    }

    @Test
    void changed_amount_currency_idempotency_or_missing_evidence_are_unknown() {
        NativeFixture f=nativeFixture();
        PointsFundsGateway adapter=adapter(f.router(),f.master());
        var invalid=List.of(
                new Receipt("OTHER",Status.SUCCEEDED,"CNY",30,"E",null),
                new Receipt("IDEM",Status.SUCCEEDED,"USD",30,"E",null),
                new Receipt("IDEM",Status.SUCCEEDED,"CNY",31,"E",null),
                new Receipt("IDEM",Status.SUCCEEDED,"CNY",30,null,null));
        for (Receipt receipt:invalid) {
            when(f.operations().executeFunds(any())).thenReturn(receipt);
            var result=adapter.submit(command());
            assertThat(result.status()).isEqualTo(FundsStatus.UNKNOWN);
            assertThat(result.evidenceNo()).isNull();
        }
    }

    @Test
    void disabled_channel_still_queries_original_request_without_resending() {
        NativeFixture f=nativeFixture();
        when(f.master().find("TEST")).thenReturn(Optional.empty());
        when(f.operations().queryFunds(nativeRequest())).thenReturn(receipt(Status.SUCCEEDED,"EVIDENCE"));
        var result=adapter(f.router(),f.master()).query(command());
        assertThat(result.status()).isEqualTo(FundsStatus.SUCCEEDED);
        verify(f.operations()).queryFunds(nativeRequest());
        verify(f.operations(),never()).executeFunds(any());
        verify(f.master(),never()).find("TEST");
    }

    @Test
    void currency_mismatch_is_rejected_before_execution() {
        NativeFixture f=nativeFixture();
        FundsCommand usd=new FundsCommand("TICKET","IDEM",FundsPurpose.SUBSIDY,context(),"USD",30);
        assertThat(adapter(f.router(),f.master()).submit(usd).reason()).isEqualTo("POINTS_CURRENCY_UNSUPPORTED");
        verify(f.operations(),never()).executeFunds(any());
    }

    @Test
    void money_operations_are_forbidden_inside_any_database_transaction() {
        NativeFixture f=nativeFixture();
        PointsFundsGateway adapter=adapter(f.router(),f.master());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.submit(command()))
                    .hasMessage("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
            assertThatThrownBy(() -> adapter.query(command()))
                    .hasMessage("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
            verify(f.operations(),never()).executeFunds(any());
            verify(f.operations(),never()).queryFunds(any());
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test
    void definitive_rejection_remains_failed_without_an_arrival_evidence() {
        NativeFixture f=nativeFixture();
        when(f.operations().executeFunds(any())).thenReturn(receipt(Status.REJECTED,"NOT_ARRIVAL"));
        var result=adapter(f.router(),f.master()).submit(command());
        assertThat(result.status()).isEqualTo(FundsStatus.FAILED);
        assertThat(result.evidenceNo()).isNull();
    }

    private NativeFixture nativeFixture() {
        var router=mock(PayGatewayRouter.class);
        var master=mock(PayChannelMasterService.class);
        PayGateway gateway=mock(PayGateway.class,withSettings().extraInterfaces(PayFundsOperations.class));
        var operations=(PayFundsOperations)gateway;
        SysPayChannel channel=new SysPayChannel();
        channel.setEnabled(true); channel.setCurrency("CNY");
        when(master.find("TEST")).thenReturn(Optional.of(channel));
        when(router.supports("TEST")).thenReturn(true);
        when(router.of("TEST")).thenReturn(gateway);
        when(operations.fundsCapabilities(any())).thenReturn(new PayFundsOperations.Capabilities(
                EnumSet.allOf(Operation.class),EnumSet.allOf(Operation.class)));
        return new NativeFixture(router,master,operations);
    }
    private Request nativeRequest() {
        return new Request("TICKET","IDEM",Operation.SUBSIDY,new PayFundsOperations.Context(
                PayFundsOperations.Path.AGGREGATED,"TEST","M","A1","SM","TRADE","ORDER",100),"CNY",30);
    }
    private Receipt receipt(Status status,String evidence) {
        return new Receipt("IDEM",status,"CNY",30,evidence,"TEST_REASON");
    }
    private record NativeFixture(PayGatewayRouter router,PayChannelMasterService master,
                                 PayFundsOperations operations) { }

    private PointsFundsGateway adapter(PayGatewayRouter router,PayChannelMasterService master) {
        try {
            return (PointsFundsGateway)Class.forName("ai.neargo.shop.pay.points.port.PayPointsFundsAdapter")
                    .getConstructor(PayGatewayRouter.class,PayChannelMasterService.class).newInstance(router,master);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Explicit points funds adapter missing",e);
        }
    }
    private FundsContext context() {
        return new FundsContext(FundsPath.AGGREGATED,"TEST","M","A1","SM","TRADE","ORDER",100);
    }
    private FundsCommand command() {
        return new FundsCommand("TICKET","IDEM",FundsPurpose.SUBSIDY,context(),"CNY",30);
    }
}
