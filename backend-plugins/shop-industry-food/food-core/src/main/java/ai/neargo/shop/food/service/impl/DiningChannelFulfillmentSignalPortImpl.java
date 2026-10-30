package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Exact original event/receipt/header scope is re-read in one local FOOD snapshot, without network
 * calls or scanning unrelated custody receipts.
 */
public class DiningChannelFulfillmentSignalPortImpl implements DiningChannelFulfillmentSignalPort {
    private final ServiceEventMapper events;
    private final KitchenTaskMapper tasks;
    private final KitchenTicketMapper tickets;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final ChannelOrderSourceMapper sources;
    private final ObjectMapper json;
    private final Clock clock;

    public DiningChannelFulfillmentSignalPortImpl(
            ServiceEventMapper events,
            KitchenTaskMapper tasks,
            KitchenTicketMapper tickets,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            ChannelOrderSourceMapper sources,
            ObjectMapper json,
            Clock clock) {
        this.events = events;
        this.tasks = tasks;
        this.tickets = tickets;
        this.batches = batches;
        this.lines = lines;
        this.sources = sources;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(
            transactionManager = "transactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Signal resolve(DiningServiceEvent event) {
        check(event != null);
        text(event.eventNo());
        text(event.entityNo());
        text(event.storeNo());
        text(event.batchNo());
        text(event.taskNo());
        check(
                "CUSTODY_HANDOVER".equals(event.action())
                        && event.qty() != null
                        && event.qty().signum() > 0
                        && event.occurredAt() != null
                        && !event.occurredAt().isAfter(clock.instant()));
        return DataScopeContext.executeWithoutScope(() -> original(event));
    }

    private Signal original(DiningServiceEvent event) {
        var stored =
                events.selectOne(
                        Wrappers.<FnbServiceEvent>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("event_no", event.eventNo())
                                .eq("task_no", event.taskNo()));
        check(
                stored != null
                        && "CUSTODY_HANDOVER".equals(stored.getAction())
                        && stored.getQty() != null
                        && stored.getQty().compareTo(event.qty()) == 0
                        && Objects.equals(stored.getReason(), event.reason())
                        && Objects.equals(stored.getCreatedBy(), event.actorNo())
                        && stored.getCustodySnapshot() != null);
        var task =
                tasks.selectOne(
                        Wrappers.<FnbKitchenTask>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("task_no", event.taskNo()));
        check(
                task != null
                        && Objects.equals(task.getTicketNo(), event.ticketNo())
                        && Objects.equals(task.getFoodLineNo(), event.foodLineNo())
                        && Objects.equals(task.getComponentNo(), event.componentNo())
                        && Objects.equals(task.getStationNo(), event.stationNo())
                        && Objects.equals(task.getTaskRole(), event.taskRole())
                        && task.getRouteVersion() != null
                        && task.getRouteVersion() == event.routeVersion());
        var ticket =
                tickets.selectOne(
                        Wrappers.<FnbKitchenTicket>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("ticket_no", task.getTicketNo()));
        check(
                ticket != null
                        && "MAKE".equals(ticket.getKind())
                        && event.batchNo().equals(ticket.getBatchNo()));
        var batch =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("store_no", event.storeNo())
                                .eq("batch_no", event.batchNo()));
        check(
                batch != null
                        && "ACCEPTED".equals(batch.getStatus())
                        && batch.getFrozenRequest() != null);
        var line =
                lines.selectOne(
                        Wrappers.<FnbOrderLine>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("batch_no", event.batchNo())
                                .eq("food_line_no", event.foodLineNo()));
        check(line != null);
        CustodyReceiptVO receipt;
        try {
            receipt = json.readValue(stored.getCustodySnapshot(), CustodyReceiptVO.class);
            check(!Instant.parse(receipt.recordedAt()).isAfter(event.occurredAt()));
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        check(
                receipt != null
                        && event.entityNo().equals(receipt.entityNo())
                        && event.storeNo().equals(receipt.storeNo())
                        && event.batchNo().equals(receipt.batchNo())
                        && "CUSTODY_HANDOVER".equals(receipt.action())
                        && Objects.equals(event.actorNo(), receipt.actorNo())
                        && FoodCommandFingerprint.hash(
                                        "custody-source:v1", batch.getFrozenRequest())
                                .equals(receipt.frozenRequestHash())
                        && receipt.lines() != null
                        && receipt.sources() != null);
        var selected =
                receipt.lines().stream()
                        .filter(
                                selectedLine ->
                                        selectedLine != null
                                                && event.foodLineNo()
                                                        .equals(selectedLine.foodLineNo()))
                        .toList();
        check(
                selected.size() == 1
                        && selected.getFirst().qty() != null
                        && selected.getFirst().qty().compareTo(event.qty()) == 0);
        var sourceLines =
                receipt.sources().stream()
                        .filter(
                                source ->
                                        source != null
                                                && event.foodLineNo().equals(source.foodLineNo()))
                        .toList();
        check(
                sourceLines.size() == 1
                        && Objects.equals(
                                line.getSourceLineRef(), sourceLines.getFirst().sourceLineRef())
                        && sourceLines.getFirst().tasks() != null);
        var custodyTasks =
                sourceLines.getFirst().tasks().stream()
                        .filter(source -> source != null && event.taskNo().equals(source.taskNo()))
                        .toList();
        check(
                custodyTasks.size() == 1
                        && Objects.equals(
                                event.componentNo(), custodyTasks.getFirst().componentNo())
                        && Objects.equals(event.stationNo(), custodyTasks.getFirst().stationNo())
                        && Objects.equals(event.taskRole(), custodyTasks.getFirst().taskRole())
                        && event.routeVersion() == custodyTasks.getFirst().routeVersion());
        if (!"CHANNEL".equals(batch.getPayMode()))
            return result(Status.NOT_CHANNEL, event, null, null, null, null, null);
        check(line.getSourceLineRef() != null);
        var source =
                sources.selectOne(
                        Wrappers.<FnbChannelOrderSource>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", event.entityNo())
                                .eq("store_no", event.storeNo())
                                .eq("batch_no", event.batchNo()));
        check(
                source != null
                        && "ACCEPTED".equals(source.getStatus())
                        && source.getImportNo() != null
                        && source.getCoreOrderNo() != null
                        && source.getCoreOrderNo().equals(receipt.orderNo())
                        && Objects.equals(source.getCoreSubOrderNo(), receipt.storeOrderNo())
                        && source.getAcceptanceReceiptRef() != null);
        if (!"DELIVERY".equals(batch.getFulfillment()) || !"DELIVERY".equals(receipt.kind()))
            return result(Status.UNSUPPORTED, event, null, null, null, null, null);
        text(receipt.operationNo());
        return result(
                Status.READY,
                event,
                source.getImportNo(),
                source.getBatchNo(),
                source.getCoreOrderNo(),
                receipt.operationNo(),
                DiningChannelFulfillmentFacts.revision(receipt));
    }

    private Signal result(
            Status status,
            DiningServiceEvent event,
            String imported,
            String batch,
            String core,
            String operation,
            String revision) {
        var now = clock.instant();
        var receipt =
                "FOOD:FULFILLMENT_SIGNAL:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        status,
                                        event,
                                        Objects.toString(imported, ""),
                                        Objects.toString(batch, ""),
                                        Objects.toString(core, ""),
                                        Objects.toString(operation, ""),
                                        Objects.toString(revision, "")));
        return new Signal(
                status,
                event,
                imported,
                batch,
                core,
                operation,
                revision,
                now,
                now.plusSeconds(30),
                receipt);
    }

    private static void text(String value) {
        check(value != null && !value.isBlank() && value.length() <= 32);
    }

    private static void check(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
