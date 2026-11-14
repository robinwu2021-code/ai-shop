package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import java.math.BigDecimal;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.impl.DiningPickupVerificationServiceImpl;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Public food identities cannot replace the original source binding or bypass local physical handover evidence. */
class DiningPickupVerificationServiceTest {
    private final OrderBatchMapper batches=mock(OrderBatchMapper.class);
    private final CoreOrderBindingMapper bindings=mock(CoreOrderBindingMapper.class);
    private final OrderLineMapper lines=mock(OrderLineMapper.class);
    private final OrderComponentMapper components=mock(OrderComponentMapper.class);
    private final KitchenTaskService kitchen=mock(KitchenTaskService.class);
    private final IndustryFulfillmentPort owner=mock(IndustryFulfillmentPort.class);
    private final DiningPickupVerificationServiceImpl service=new DiningPickupVerificationServiceImpl(
            batches,bindings,kitchen,() -> owner,lines,components);
    @Test void noLocalHandoverCannotIssueOriginalPickup() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of());
        assertThatThrownBy(() -> service.verify("E","S","actor","B",new VerifyPickupReq(0L,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(owner,never()).verifyPickup(any(),anyString());
    }
    @Test void readProjectsRealOriginalReferenceAndKeepsLocalFactSeparate() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of());
        when(owner.query(any(),anyString(),anyString())).thenAnswer(i -> {
            IndustryFulfillmentPort.Reference ref=i.getArgument(0);
            assertThat(ref.orderNo()).isEqualTo("ORIGINAL_ROOT"); assertThat(ref.subOrderNo()).isEqualTo("ORIGINAL_CHILD");
            assertThat(ref.source().sourceNo()).isEqualTo("ORIGINAL_CART");
            assertThat(ref.source().sourceVersion()).isEqualTo(4);
            return new IndustryFulfillmentPort.Receipt(null,ref,"NOT_STARTED","WAIT_FULFILL",true,true,
                    null,null,3,12);
        });
        var result=service.get("E","S","actor","B","key");
        assertThat(result.localHandoverRecorded()).isFalse();
        assertThat(result.originalFulfillment().subOrderVersion()).isEqualTo(12);
        verify(owner,never()).verifyPickup(any(),anyString());
    }
    @Test void malformedCodeNeverTouchesOwner() {
        assertThatThrownBy(() -> service.verify("E","S","actor","B",new VerifyPickupReq(0L,"12"),"key"))
                .isInstanceOf(BizException.class);
        verify(owner,never()).verifyPickup(any(),anyString());
    }
    @Test void partialVoidAndNetHandoverCannotIssueWholeOriginalItems() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L",null,"2","1","MAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("HANDOVER","1")));
        rejected();
    }
    @Test void preDispatchCancellationWithZeroVoidStillCannotIssueOriginalFullQuantity() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L",null,"1","0","MAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("HANDOVER","1")));
        rejected();
    }
    @Test void omittedOriginalLineCannotBeHiddenByAnotherCompletedTask() {
        fixture(); when(lines.selectList(any())).thenReturn(List.of(line("L","2"),line("OTHER","1")));
        when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L",null,"2","0","MAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("HANDOVER","2")));
        rejected();
    }
    @Test void componentTasksMustRetainOriginalFrozenComponentQuantities() {
        fixture(); var part=new FnbOrderComponent(); part.setEntityNo("E"); part.setFoodLineNo("L");
        part.setComponentNo("P"); part.setQty(new BigDecimal("4"));
        when(components.selectList(any())).thenReturn(List.of(part));
        when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L","P","2","0","MAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("HANDOVER","2")));
        rejected();
        when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L","P","4","0","MAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("HANDOVER","4")));
        acceptsOriginal();
    }
    @Test void lossAndRemakeWithFullOriginalHandoverRemainEligible() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(
                task("T","L",null,"2","0","MAKE"),task("R","L",null,"1","0","REMAKE")));
        when(kitchen.events("E","S","T")).thenReturn(List.of(event("LOSS","1"),event("HANDOVER","2")));
        acceptsOriginal();
    }
    @Test void compatibilityConstructorWithoutOriginalQuantityProofFailsClosed() {
        fixture(); when(kitchen.queue("E","S",null,"B")).thenReturn(List.of(task("T","L",null,"2","0","MAKE")));
        var unavailable=new DiningPickupVerificationServiceImpl(batches,bindings,kitchen,() -> owner);
        assertThatThrownBy(() -> unavailable.verify("E","S","actor","B",new VerifyPickupReq(0L,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(owner,never()).verifyPickup(any(),anyString());
    }
    private void rejected() {
        assertThatThrownBy(() -> service.verify("E","S","actor","B",new VerifyPickupReq(0L,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(owner,never()).verifyPickup(any(),anyString());
    }
    private void acceptsOriginal() {
        when(owner.verifyPickup(any(),anyString())).thenAnswer(i -> {
            IndustryFulfillmentPort.VerifyPickup command=i.getArgument(0);
            return new IndustryFulfillmentPort.Receipt("FULFILL",command.reference(),"VERIFIED","COMPLETED",false,false,
                    "RESERVATION","ISSUED_UNPAID",3,1);
        });
        assertThat(service.verify("E","S","actor","B",new VerifyPickupReq(0L,"123456"),"key")
                .originalFulfillment().status()).isEqualTo("VERIFIED");
    }
    private FnbOrderLine line(String number,String qty) {
        var line=new FnbOrderLine(); line.setEntityNo("E"); line.setBatchNo("B");
        line.setFoodLineNo(number); line.setQty(new BigDecimal(qty)); return line;
    }
    private TaskVO task(String number,String line,String part,String qty,String voidQty,String kind) {
        return new TaskVO(number,"TICKET","B",line,part,"STATION","MAKE",1,new BigDecimal(qty),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal(voidQty),"SERVED",null,null,0,kind,null);
    }
    private EventVO event(String action,String qty) { return new EventVO("EVENT","T",action,new BigDecimal(qty),null,null); }
    private void fixture() {
        when(lines.selectList(any())).thenReturn(List.of(line("L","2")));
        when(components.selectList(any())).thenReturn(List.of());
        var batch=new FnbOrderBatch(); batch.setBatchNo("B"); batch.setEntityNo("E"); batch.setStoreNo("S");
        batch.setStatus("ACCEPTED"); batch.setAcceptedAt(LocalDateTime.now()); batch.setFulfillment("PICKUP");
        batch.setCartNo("ORIGINAL_CART"); batch.setCartVersion(4L); when(batches.selectOne(any())).thenReturn(batch);
        var binding=new FnbCoreOrderBinding(); binding.setStatus("BOUND"); binding.setPaymentRootNo("ORIGINAL_ROOT");
        binding.setStoreOrderNo("ORIGINAL_CHILD"); when(bindings.selectOne(any())).thenReturn(binding);
    }
}
