package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.service.impl.StockReplenishmentServiceImpl;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class StockReplenishmentServiceTest {
    final StockReplenishmentAdmissionService admissions=mock(StockReplenishmentAdmissionService.class);
    static final Scope S=new Scope("MAIN","E","S");
    @Test void manualRecoveryAndCancellationStopBeforeSourceLookupOrNewSourceClaims(){
        var factory=new DefaultListableBeanFactory();var target=mock(FoodStockReplenishmentPort.class);
        var source=mock(StockReplenishmentSourcePort.class);factory.registerSingleton("target",target);factory.registerSingleton("source",source);
        var service=new StockReplenishmentServiceImpl(factory.getBeanProvider(FoodStockReplenishmentPort.class),
                factory.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        var mapping=new FoodStockReplenishmentPort.Mapping("RAW","G",BigDecimal.ONE);
        var original=mock(FoodStockReplenishmentPort.View.class);
        when(target.recoverManualRegistration(any(),eq("A"),eq("old"),eq("OUT"),eq(1),eq(mapping),eq("MOV"))).thenReturn(original);
        assertThat(service.registerManual(S,"A","old","OUT",1,mapping,"MOV")).isSameAs(original);
        when(target.recoverManualRegistration(any(),eq("A"),eq("sealed"),any(),anyInt(),any(),any()))
                .thenThrow(BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT));
        assertThatThrownBy(()->service.registerManual(S,"A","sealed","OUT",1,mapping,"MOV")).isInstanceOf(BizException.class);
        verifyNoInteractions(source,admissions);
    }
    @Test void trustedSourceCompletesBeforeTargetAndWrongStoreNeverWrites(){
        var factory=new DefaultListableBeanFactory();var source=mock(StockReplenishmentSourcePort.class);var target=mock(FoodStockReplenishmentPort.class);
        factory.registerSingleton("source",source);factory.registerSingleton("target",target);
        var service=new StockReplenishmentServiceImpl(factory.getBeanProvider(FoodStockReplenishmentPort.class),factory.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        var fact=new StockReplenishmentSourcePort.Outbound("INV","OWNER","OUT",1,"I","G",BigDecimal.TEN,"S");
        var mapping=new FoodStockReplenishmentPort.Mapping("RAW","G",BigDecimal.ONE);
        var proof=new StockReplenishmentClaim("SRP","FOOD_LOCAL","binding");
        when(admissions.claimFood(any(),any(),any(),any())).thenReturn(proof);
        when(source.outbound(S,"ACTOR","OUT",1)).thenReturn(fact);service.register(S,"ACTOR","key","OUT",1,mapping);
        var order=inOrder(source,admissions,target);order.verify(source).outbound(S,"ACTOR","OUT",1);
        order.verify(admissions).claimFood(S,"ACTOR",fact,new StockReplenishmentAdmissionService.Mapping("RAW","G",BigDecimal.ONE));
        order.verify(target).registerClaimed(new FoodLocalStockPort.Scope("E","S"),"ACTOR","key",fact,mapping,proof);
        reset(target);when(source.outbound(S,"ACTOR","OUT",1)).thenReturn(new StockReplenishmentSourcePort.Outbound("INV","OWNER","OUT",1,"I","G",BigDecimal.TEN,"FOREIGN"));
        assertThatThrownBy(()->service.register(S,"ACTOR","key","OUT",1,mapping)).isInstanceOf(BizException.class);verify(target,never()).registerClaimed(any(),any(),any(),any(),any(),any());
    }
    @Test void sourceOutageDoesNotBlockOriginalLocalResultLookupAndNeverFallsBack(){
        var factory=new DefaultListableBeanFactory();var target=mock(FoodStockReplenishmentPort.class);factory.registerSingleton("target",target);
        var service=new StockReplenishmentServiceImpl(factory.getBeanProvider(FoodStockReplenishmentPort.class),factory.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        service.get(S,"FRP");service.findCommand(S,"REPLENISH_RECEIVE","key");
        verify(target).get(new FoodLocalStockPort.Scope("E","S"),"FRP");verify(target).findCommand(new FoodLocalStockPort.Scope("E","S"),"REPLENISH_RECEIVE","key");
        clearInvocations(target);assertThatThrownBy(()->service.register(S,"A","key","OUT",1,new FoodStockReplenishmentPort.Mapping("I","G",BigDecimal.ONE))).isInstanceOf(BizException.class);
        verify(target,never()).registerClaimed(any(),any(),any(),any(),any(),any());
    }    @Test void originalRegistrationAndConfigurationRecoverBeforeAnySourceRequest(){
        var factory=new DefaultListableBeanFactory();var target=mock(FoodStockReplenishmentPort.class);factory.registerSingleton("target",target);
        var service=new StockReplenishmentServiceImpl(factory.getBeanProvider(FoodStockReplenishmentPort.class),factory.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        var mapping=new FoodStockReplenishmentPort.Mapping("RAW","G",BigDecimal.ONE);var restored=mock(FoodStockReplenishmentPort.View.class);
        when(target.recoverRegistration(any(),eq("A"),eq("old"),eq("OUT"),eq(1),eq(mapping))).thenReturn(restored);
        assertThat(service.register(S,"A","old","OUT",1,mapping)).isSameAs(restored);
        var change=new FoodStockReplenishmentPort.ConnectionChange(true,0,"on");var connection=new FoodStockReplenishmentPort.Connection(true,1,1,"INV","OWNER",10);
        when(target.recoverConfiguration(any(),eq("A"),eq("on"),eq(change))).thenReturn(connection);
        assertThat(service.configure(S,"A","on",change)).isEqualTo(connection);
        verify(target,never()).registerClaimed(any(),any(),any(),any(),any(),any());verify(target,never()).configure(any(),any(),any(),any(),any());verifyNoInteractions(admissions);
    }
    @Test void closingNeedsNoSourceButOpeningWithoutCheckpointFailsClosed(){
        var factory=new DefaultListableBeanFactory();var target=mock(FoodStockReplenishmentPort.class);factory.registerSingleton("target",target);
        var service=new StockReplenishmentServiceImpl(factory.getBeanProvider(FoodStockReplenishmentPort.class),factory.getBeanProvider(StockReplenishmentSourcePort.class),admissions);
        when(target.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(true,1,1,"INV","OWNER",10));
        var close=new FoodStockReplenishmentPort.ConnectionChange(false,1,"stop");service.configure(S,"A","off",close);
        verify(target).configure(new FoodLocalStockPort.Scope("E","S"),"A","off",close,null);
        when(target.connection(any())).thenReturn(new FoodStockReplenishmentPort.Connection(false,1,2,"INV","OWNER",10));
        assertThatThrownBy(()->service.configure(S,"A","on",new FoodStockReplenishmentPort.ConnectionChange(true,2,"on"))).isInstanceOf(BizException.class);
    }

}
