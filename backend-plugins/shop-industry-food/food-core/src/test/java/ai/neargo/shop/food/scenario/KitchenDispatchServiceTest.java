package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CourseMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

/** Dispatch authority checks; these isolated tests do not prove database lock behavior. */
class KitchenDispatchServiceTest {
    private final OrderBatchMapper batches=mock(OrderBatchMapper.class);
    private final KitchenTicketMapper tickets=mock(KitchenTicketMapper.class);
    private final KitchenTaskMapper tasks=mock(KitchenTaskMapper.class);
    private final ConfigRevisionMapper revisions=mock(ConfigRevisionMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private KitchenTaskServiceImpl service() {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new KitchenTaskServiceImpl(batches,mock(OrderLineMapper.class),mock(OrderComponentMapper.class),
                revisions,tickets,tasks,mock(ServiceEventMapper.class),mock(CourseMapper.class),mock(CheckMapper.class),
                mock(IdempotencyService.class),new ObjectMapper(),manager,mock(OutboxPublisher.class));
    }
    @Test void denyDispatchBeforeActualAcceptanceEvenWithAcceptedTimestamp() {
        var service=service(); var row=batch("BOUND");
        when(batches.selectOne(any())).thenReturn(row);
        assertThatThrownBy(() -> service.accepted("E","S","B","original-event")).isInstanceOf(BizException.class);
        verify(tickets,never()).insert(any(FnbKitchenTicket.class));
    }
    @Test void historicalAcceptedWithoutFrozenRoutingDoesNotInferCurrentStation() {
        var service=service(); var row=batch("ACCEPTED"); row.setFrozenRequest("{\"schema\":1}");
        when(batches.selectOne(any())).thenReturn(row); when(tickets.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.accepted("E","S","B","original-event")).isInstanceOf(BizException.class);
        verify(tickets,never()).stationPublications(any()); verify(tickets,never()).insert(any(FnbKitchenTicket.class));
    }
    @Test void acceptedReplayReusesOriginalDispatchEvenWithAnotherOutboxDeliveryKey() {
        var service=service(); when(batches.selectOne(any())).thenReturn(batch("ACCEPTED"));
        var original=new FnbKitchenTicket(); original.setTicketNo("T"); original.setBatchNo("B");
        original.setKind("MAKE"); original.setStatus("QUEUED");
        when(tickets.selectList(any())).thenReturn(List.of(original)); when(tasks.selectList(any())).thenReturn(List.of());
        assertThat(service.accepted("E","S","B","original-event-2").ticketNo()).isEqualTo("T");
        verify(tickets,never()).insert(any(FnbKitchenTicket.class));
    }
    @Test void unownedBatchCannotCreateTicket() {
        var service=service(); when(batches.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.accepted("E","OTHER","B","original-event"))
                .isInstanceOf(BizException.class);
        verify(tickets,never()).insert(any(FnbKitchenTicket.class));
    }
    private static FnbOrderBatch batch(String status) {
        var batch=new FnbOrderBatch(); batch.setEntityNo("E"); batch.setStoreNo("S"); batch.setBatchNo("B");
        batch.setStatus(status); batch.setAcceptedAt(LocalDateTime.now()); return batch;
    }
}
