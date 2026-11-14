package ai.neargo.shop.pay.numbering;
import ai.neargo.shop.pay.numbering.impl.StoredValueNumberedPlanRechargeServiceImpl;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.*;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class StoredValueNumberedPlanRechargeServiceTest {
    @Test void reservesBeforeTransactionAndBindsOnlyAfterSuccessfulOriginalCreation() {
        var direct=mock(StoredValuePlanRechargeService.class);var rows=mock(SvRechargeMapper.class);
        var generator=mock(DocumentNumberGenerator.class);var numbers=mock(DocumentNumberService.class);
        var ctx=new EntitlementContext("MAIN","ACTOR","REQUEST","0".repeat(64));
        var command=new Create("USER","STORE","门店","PLAN","VERSION","a".repeat(64));
        var reserved=new ReservedDocumentNumber(DocumentType.SV_RECHARGE,LocalDate.of(2026,10,20),"PR20261020000001");
        when(generator.reserve(eq(DocumentType.SV_RECHARGE),any(java.time.Instant.class))).thenAnswer(a->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return reserved;});
        var row=new SvRecharge();row.setRechargeNo("internal");
        when(rows.find(eq("MAIN"),anyString())).thenReturn(null,row);
        when(numbers.findBySource(eq(DocumentType.SV_RECHARGE),any())).thenReturn(Optional.empty());
        when(numbers.bind(eq(DocumentType.SV_RECHARGE),any(),eq(reserved))).thenAnswer(a->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return reserved.value();});
        var original=new RechargeView("internal",RechargeState.CREDITED,"USER","ENTITY","STORE","CNY",100L,100L,null,null,1);
        when(direct.confirmOffline(ctx,"ENTITY",command)).thenReturn(original);
        var service=new StoredValueNumberedPlanRechargeServiceImpl(direct,rows,generator,numbers,
            new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:numbered-facade;MODE=MySQL","sa","")),new ObjectMapper(),reservations());
        assertThat(service.confirmOffline(ctx,"ENTITY",command).documentNo()).isEqualTo(reserved.value());
        verify(numbers).bind(eq(DocumentType.SV_RECHARGE),any(),eq(reserved));
    }
    @Test void historicalQueryNeverAllocatesOrBindsAndOldJsonRemainsReadable() {
        var direct=mock(StoredValuePlanRechargeService.class);var rows=mock(SvRechargeMapper.class);
        var generator=mock(DocumentNumberGenerator.class);var numbers=mock(DocumentNumberService.class);
        var ctx=new EntitlementContext("MAIN","ACTOR","REQUEST","0".repeat(64));
        var original=new RechargeView("old",RechargeState.CREDITED,"USER","ENTITY","STORE","CNY",100L,100L,null,null,1);
        when(direct.query(ctx,"ENTITY","old")).thenReturn(original);
        when(numbers.findBySource(eq(DocumentType.SV_RECHARGE),any())).thenReturn(Optional.empty());
        var service=new StoredValueNumberedPlanRechargeServiceImpl(direct,rows,generator,numbers,
            new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:history-facade","sa","")),new ObjectMapper(),reservations());
        assertThat(service.query(ctx,"ENTITY","old").documentNo()).isNull();
        verifyNoInteractions(generator);verify(numbers,never()).bind(any(),any(),any());
        var json=new ObjectMapper();
        String oldJson=json.writeValueAsString(original).replace(",\"documentNo\":null", "");
        assertThat(json.readValue(oldJson,RechargeView.class).rechargeNo()).isEqualTo("old");
    }
    @Test void shortNumberLookupUsesResolvedOriginalBeforeBusinessAuthorization() {
        var direct=mock(StoredValuePlanRechargeService.class);var rows=mock(SvRechargeMapper.class);
        var generator=mock(DocumentNumberGenerator.class);var numbers=mock(DocumentNumberService.class);
        var ctx=new EntitlementContext("MAIN","ACTOR","REQUEST","0".repeat(64));
        String number="PR20261020000001";
        var source=new DocumentSource("MAIN","PAY","SV_RECHARGE","internal");
        when(numbers.resolve("MAIN",DocumentType.SV_RECHARGE,number)).thenReturn(Optional.of(source));
        when(numbers.findBySource(DocumentType.SV_RECHARGE,source)).thenReturn(Optional.of(number));
        when(direct.query(ctx,"ENTITY","internal")).thenReturn(new RechargeView("internal",RechargeState.CREDITED,
            "USER","ENTITY","STORE","CNY",100L,100L,null,null,1));
        var service=new StoredValueNumberedPlanRechargeServiceImpl(direct,rows,generator,numbers,
            new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:query-facade","sa","")),new ObjectMapper(),reservations());
        var result=service.query(ctx,"ENTITY",number);
        verify(direct).query(ctx,"ENTITY","internal");
        assertThat(result.documentNo()).isEqualTo(number);
        verifyNoInteractions(generator);
    }
    private static DocumentNumberCoordinator reservations() {
        var coordinator=mock(DocumentNumberCoordinator.class);
        when(coordinator.withReservation(anyMap(),any())).thenAnswer(invocation->
            ((java.util.function.Supplier<?>)invocation.getArgument(1)).get());
        return coordinator;
    }

}
