package ai.neargo.shop.unit;

import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValuePlanRechargeServiceImpl;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StoredValueOfflineCreditResultTest {
    @Test void returnsThePostingResultWhenAnOldReadStillSeesReceived() {
        var recharges=mock(SvRechargeMapper.class);var credits=mock(StoredValueCreditService.class);
        var json=mock(ObjectMapper.class);
        var service=spy(new StoredValuePlanRechargeServiceImpl(null,recharges,null,null,null,credits,null,null,json,null));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"issuerLedger",
                new ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerLedgerBridge(null,null,null,false));
        var ctx=new EntitlementContext("MAIN","OWNER","retry","0".repeat(64));
        var command=mock(Create.class);var old=mock(RechargeView.class);var credited=mock(RechargeView.class);
        when(old.status()).thenReturn(RechargeState.RECEIVED);when(old.rechargeNo()).thenReturn("R1");
        when(credited.status()).thenReturn(RechargeState.CREDITED);
        doReturn(old).when(service).create(ctx,"ENTITY",command);
        doReturn(old).when(service).query(ctx,"ENTITY","R1");
        var row=new SvRecharge();row.setEntityNo("ENTITY");row.setSourceKind("PLAN");row.setStatus("RECEIVED");
        row.setRechargeNo("R1");row.setReceivedDecisionSnapshot("{}");
        when(recharges.find("MAIN","R1")).thenReturn(row);
        when(json.readValue("{}",DecisionRef.class)).thenReturn(mock(DecisionRef.class));
        when(credits.credit(eq(ctx),any())).thenReturn(credited);
        assertThat(service.confirmOffline(ctx,"ENTITY",command)).isSameAs(credited);
    }
}
