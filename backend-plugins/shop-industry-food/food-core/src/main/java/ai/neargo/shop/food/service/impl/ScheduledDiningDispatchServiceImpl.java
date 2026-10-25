package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import ai.neargo.shop.spi.printing.PrintPort;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

/** Short committed leases surround owner calls; ticket and print identities survive every retry. */
public class ScheduledDiningDispatchServiceImpl implements ScheduledDiningDispatchService {
    private final FoodBusinessOperationsMapper mapper;
    private final TransactionTemplate tx;
    private final KitchenTaskService kitchen;
    private final Supplier<PrintPort> printing;
    private final FoodPrintService documents;
    private final Clock clock;

    public ScheduledDiningDispatchServiceImpl(
            FoodBusinessOperationsMapper mapper,
            PlatformTransactionManager manager,
            KitchenTaskService kitchen,
            Supplier<PrintPort> printing,
            FoodPrintService documents,
            Clock clock) {
        this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        this.kitchen = kitchen;
        this.printing = printing;
        this.documents = documents;
        this.clock = clock;
    }

    @Override
    public Report runDue(int limit) {
        if (limit < 1 || limit > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dispatch owner calls cannot hold a FOOD transaction");
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var candidates = mapper.dueScheduledDispatch(now, limit);
        int claimed = 0, completed = 0, pending = 0;
        for (var row : candidates) {
            String e = row.text("entity_no"),
                    store = row.text("store_no"),
                    batch = row.text("batch_no");
            long version = row.number("version");
            if (!Boolean.TRUE.equals(
                    tx.execute(
                            t ->
                                    mapper.claimScheduledDispatch(
                                                    e,
                                                    store,
                                                    batch,
                                                    version,
                                                    now,
                                                    now.plusMinutes(2))
                                            == 1))) continue;
            claimed++;
            try {
                var original = mapper.capacityOriginalBatch(e, store, batch);
                if (original.size() != 1)
                    throw new IllegalStateException("Missing original scheduled batch");
                if ("CANCELLED".equals(original.getFirst().get("status"))) {
                    tx.execute(
                            t ->
                                    mapper.finishScheduledDispatch(
                                            e, store, batch, version + 1, "CANCELLED", null, now));
                    completed++;
                    continue;
                }
                var ticket = kitchen.accepted(e, store, batch, "SCHEDULED_DISPATCH:" + batch);
                print(e, store, batch, ticket);
                tx.execute(
                        t ->
                                mapper.finishScheduledDispatch(
                                        e, store, batch, version + 1, "COMPLETED", null, now));
                completed++;
            } catch (RuntimeException failure) {
                String error = failure.getClass().getSimpleName();
                tx.execute(
                        t ->
                                mapper.finishScheduledDispatch(
                                        e,
                                        store,
                                        batch,
                                        version + 1,
                                        "RETRY",
                                        error,
                                        now.plusMinutes(2)));
                pending++;
            }
        }
        return new Report(claimed, completed, pending);
    }

    private void print(String e, String store, String batch, TicketVO ticket) {
        if (ticket == null
                || ticket.ticketNo() == null
                || "SCHEDULED".equals(ticket.status())
                || !batch.equals(ticket.batchNo())
                || !"MAKE".equals(ticket.kind())
                || ticket.tasks() == null)
            throw new IllegalStateException("Invalid original kitchen ticket");
        var owner = printing.get();
        if (owner == null) throw new IllegalStateException("Print owner unavailable");
        var scope = new PrintPort.Scope(e, store, "SYSTEM");
        var configuration = owner.configuration(scope);
        if (configuration == null) throw new IllegalStateException("Missing print configuration");
        var stations = new HashSet<String>();
        for (var task : ticket.tasks()) {
            if (!batch.equals(task.batchNo()) || !ticket.ticketNo().equals(task.ticketNo()))
                throw new IllegalStateException("Foreign kitchen task");
            stations.add(task.stationNo());
        }
        var requests = new LinkedHashSet<PrintDocumentReq>();
        for (var route : configuration.routes()) {
            if ("ORDER".equals(route.scene()) && route.stationNo() == null)
                requests.add(new PrintDocumentReq("ORDER", batch, null));
            else if ("KITCHEN".equals(route.scene()) && stations.contains(route.stationNo()))
                requests.add(new PrintDocumentReq("KITCHEN", batch, route.stationNo()));
        }
        for (var request : requests)
            documents.enqueue(
                    scope,
                    request,
                    "AUTO_PRINT_V1:"
                            + batch
                            + ":"
                            + request.scene()
                            + ":"
                            + (request.stationNo() == null ? "" : request.stationNo()));
    }
}
