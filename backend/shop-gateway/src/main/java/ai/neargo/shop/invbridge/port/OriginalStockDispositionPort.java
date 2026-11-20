package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Frozen authority selects the real original owner, independently of today's primary StockPort. */
@Component
@Primary
public class OriginalStockDispositionPort implements StockDispositionPort {
    private final StockDispositionService platform;
    private final ObjectProvider<ReservationDispositionService> inventory;
    private final ObjectProvider<InventoryStockPort> inventoryCommands;
    private final ObjectProvider<DualWriteStockPort> dualCommands;
    private TransactionTemplate ownerWrites;

    @Autowired
    public OriginalStockDispositionPort(
            StockDispositionService platform,
            ObjectProvider<ReservationDispositionService> inventory,
            ObjectProvider<InventoryStockPort> inventoryCommands,
            ObjectProvider<DualWriteStockPort> dualCommands,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(platform, inventory, inventoryCommands, dualCommands);
        ownerWrites = new TransactionTemplate(manager);
    }

    public OriginalStockDispositionPort(
            StockDispositionService platform,
            ObjectProvider<ReservationDispositionService> inventory,
            ObjectProvider<InventoryStockPort> inventoryCommands,
            ObjectProvider<DualWriteStockPort> dualCommands) {
        this.platform = platform;
        this.inventory = inventory;
        this.inventoryCommands = inventoryCommands;
        this.dualCommands = dualCommands;
    }

    @Override
    public ReservationView reserveOriginal(Reservation request) {
        outside();
        try {
            if ("INVENTORY".equals(request.original().authority())) {
                var adapter = inventoryCommands.getIfAvailable();
                if (adapter == null) {
                    return reservationUnavailable(
                            request.original(), request.reservationOperationNo());
                }
                adapter.lock(
                        request.original().orderNo(),
                        request.original().needs().stream()
                                .map(
                                        need ->
                                                new StockPort.SkuQty(
                                                        need.skuNo(),
                                                        need.quantity(),
                                                        need.sourceStoreNo(),
                                                        need.fulfillmentRef(),
                                                        request.original().lifecycleVersion()))
                                .toList());
            } else if ("DUAL".equals(request.original().authority())) {
                var adapter = dualCommands.getIfAvailable();
                if (adapter == null || ownerWrites == null) {
                    return reservationUnavailable(
                            request.original(), request.reservationOperationNo());
                }
                return ownerWrites.execute(
                        tx -> {
                            var before =
                                    platform.observeReservation(
                                            new ReservationLookup(
                                                    request.original(),
                                                    request.reservationOperationNo()));
                            if ("UNKNOWN".equals(before.outcome()) && before.lines().isEmpty()) {
                                platform.reserveOriginal(request);
                                adapter.lock(
                                        request.original().orderNo(),
                                        request.original().needs().stream()
                                                .map(
                                                        need ->
                                                                new StockPort.SkuQty(
                                                                        need.skuNo(),
                                                                        need.quantity(),
                                                                        need.sourceStoreNo(),
                                                                        need.fulfillmentRef(),
                                                                        request.original()
                                                                                .lifecycleVersion()))
                                                .toList());
                            }
                            return platform.observeReservation(
                                    new ReservationLookup(
                                            request.original(), request.reservationOperationNo()));
                        });
            } else {
                platform.reserveOriginal(request);
            }
            return observeReservation(
                    new ReservationLookup(request.original(), request.reservationOperationNo()));
        } catch (RuntimeException unavailable) {
            return observeReservation(
                    new ReservationLookup(request.original(), request.reservationOperationNo()));
        }
    }

    @Override
    public ReleaseView releaseOriginal(ReservationLookup request) {
        outside();
        try {
            if ("INVENTORY".equals(request.original().authority())) {
                var owner = inventory.getIfAvailable();
                return owner == null ? releaseUnavailable(request) : owner.releaseOriginal(request);
            }
            if ("DUAL".equals(request.original().authority())) {
                var adapter = dualCommands.getIfAvailable();
                if (adapter == null || ownerWrites == null) {
                    return releaseUnavailable(request);
                }
                return ownerWrites.execute(
                        tx -> {
                            var before = platform.observeReservation(request);
                            if ("RESERVED".equals(before.outcome())) {
                                adapter.release(request.original().orderNo());
                            }
                            return platform.releaseOriginal(request);
                        });
            }
            return platform.releaseOriginal(request);
        } catch (RuntimeException unavailable) {
            return releaseUnavailable(request);
        }
    }

    private ReleaseView releaseUnavailable(ReservationLookup request) {
        return new ReleaseView(
                request.original(),
                request.reservationOperationNo(),
                "UNKNOWN",
                List.of(),
                StockDispositionContract.hash(
                        "STOCK_RESERVATION_RELEASE_V1:UNAVAILABLE:"
                                + request.reservationOperationNo()),
                Instant.now(),
                "STOCK_OWNER_UNAVAILABLE");
    }

    @Override
    public ReservationView observeReservation(ReservationLookup request) {
        outside();
        try {
            if ("INVENTORY".equals(request.original().authority())) {
                var owner = inventory.getIfAvailable();
                return owner == null
                        ? reservationUnavailable(
                                request.original(), request.reservationOperationNo())
                        : owner.observeReservation(request);
            }
            return platform.observeReservation(request);
        } catch (RuntimeException unavailable) {
            return reservationUnavailable(request.original(), request.reservationOperationNo());
        }
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("counter stock IO requires released Core roots");
        }
    }

    private ReservationView reservationUnavailable(Original original, String operation) {
        return new ReservationView(
                original,
                operation,
                "UNKNOWN",
                List.of(),
                StockDispositionContract.hash("STOCK_RESERVATION_UNAVAILABLE" + operation),
                Instant.now(),
                "STOCK_OWNER_UNAVAILABLE");
    }

    @Override
    public Disposition confirmOriginal(Confirmation command) {
        try {
            var result = confirmOwner(command);
            if ("INVENTORY".equals(command.original().authority())
                    && command.original().lifecycleVersion() == 2
                    && result.outcome() == Outcome.PENDING) {
                var adapter = inventoryCommands.getIfAvailable();
                if (adapter == null) {
                    return unavailable(command.original(), command.operationNo());
                }
                adapter.confirm(command.original().orderNo(), 2);
            } else if ("DUAL".equals(command.original().authority())
                    && result.outcome() == Outcome.APPLIED) {
                var adapter = dualCommands.getIfAvailable();
                if (adapter != null) {
                    adapter.confirm(
                            command.original().orderNo(), command.original().lifecycleVersion());
                }
            }
            // The command-return/queue result is discarded; read the actual committed owner again.
            return observeOwner(new Lookup(command.original(), command.operationNo()));
        } catch (RuntimeException unavailable) {
            return unavailable(command.original(), command.operationNo());
        }
    }

    @Override
    public Disposition observeOriginal(Lookup query) {
        try {
            return observeOwner(query);
        } catch (RuntimeException unavailable) {
            return unavailable(query.original(), query.operationNo());
        }
    }

    private Disposition confirmOwner(Confirmation command) {
        if ("INVENTORY".equals(command.original().authority())) {
            var owner = inventory.getIfAvailable();
            return owner == null
                    ? unavailable(command.original(), command.operationNo())
                    : owner.confirmOriginal(command);
        }
        return platform.confirmOriginal(command);
    }

    private Disposition observeOwner(Lookup query) {
        if ("INVENTORY".equals(query.original().authority())) {
            var owner = inventory.getIfAvailable();
            return owner == null
                    ? unavailable(query.original(), query.operationNo())
                    : owner.observeOriginal(query);
        }
        return platform.observeOriginal(query);
    }

    private Disposition unavailable(Original original, String operation) {
        return new Disposition(
                original,
                operation,
                Outcome.UNKNOWN,
                CurrentState.MISSING,
                List.of(),
                StockDispositionContract.hash("UNAVAILABLE" + operation),
                Instant.now(),
                "STOCK_OWNER_UNAVAILABLE");
    }
}
