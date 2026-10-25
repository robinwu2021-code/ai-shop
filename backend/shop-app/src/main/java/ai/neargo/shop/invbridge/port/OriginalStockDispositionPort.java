package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;

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
