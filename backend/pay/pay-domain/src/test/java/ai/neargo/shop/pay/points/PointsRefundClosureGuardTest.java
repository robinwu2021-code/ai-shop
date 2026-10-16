package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundLine;
import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyString;

class PointsRefundClosureGuardTest {
    @Test void a_stale_transaction_cannot_ignore_a_committed_closure_of_grant_only_responsibility() {
        var read=mock(PointsReadStore.class);
        var config=mock(PointsConfigurationStore.class);
        var posting=mock(PointsPostingService.class);
        when(read.position(anyString(),anyString())).thenReturn(null);
        when(read.type("TYPE")).thenReturn(Optional.of(new PointType("TYPE","CN","CNY",2,
                new RedeemRate(1,1),3000,365,PointType.Status.ACTIVE)));
        when(read.lifecycle("TYPE","SUB")).thenReturn(Map.of("payload_json","{}"));
        when(config.lockLifecycle("TYPE","SUB"))
                .thenReturn(Map.of("payload_json","{\"refundClosedFact\":{\"sourceVersion\":3}}"));
        var beans=new StaticListableBeanFactory();
        var service=new PointsRefundPreparationServiceImpl(read,config,posting,
                beans.getBeanProvider(PointsFundsGateway.class),beans.getBeanProvider(StoreStatementService.class));
        var command=new RefundCommand("AS","SUB","USER","TYPE","CNY",1,1,1,
                List.of(new RefundLine("I",1)),new PaymentSnapshot("DIRECT","TEST","M","STORE","A1",
                "SM","TRADE","OUT",10));
        assertThatThrownBy(() -> service.prepare(command)).hasMessage("POINTS_REFUND_RESPONSIBILITY_CLOSED");
        verifyNoInteractions(posting);
    }
}
