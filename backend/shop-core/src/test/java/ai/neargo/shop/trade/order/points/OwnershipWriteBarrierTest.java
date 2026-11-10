package ai.neargo.shop.trade.order.points;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.points.PointsOrderEvent;
import ai.neargo.shop.spi.points.SourceFactsPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.trade.order.mapper.OrderPointsFactMapper;
import ai.neargo.shop.trade.order.points.impl.OrderPointsFactServiceImpl;
import org.junit.jupiter.api.Test;

class OwnershipWriteBarrierTest {
    @Test void frozenOrChangedOwnershipStopsSourceFactsBeforeAnyJournalOrBindingWrite() {
        var mapper=mock(OrderPointsFactMapper.class);
        var bindings=mock(OrderPointsBindingService.class);
        var outbox=mock(OutboxEventBus.class);
        var ownership=mock(StoreOwnershipWritePort.class);
        var service=new OrderPointsFactServiceImpl(mapper,bindings,outbox);
        service.setOwnershipWrites(ownership);
        var draft=mock(PointsOrderEvent.class);
        when(draft.fact()).thenReturn(mock(SourceFactsPort.Fact.class));
        when(draft.merchantNo()).thenReturn("M");
        when(draft.storeNo()).thenReturn("S");
        doThrow(BizException.of(ErrorCode.CONFLICT)).when(ownership).lockAndCheck("MAIN","M","S");
        assertThrows(BizException.class,()->service.append(draft));
        verifyNoInteractions(mapper,bindings,outbox);
    }
}
