package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.service.BalanceQueryService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.StockHoldService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 余额与人工冻结（TDD §2.7、§2.11）。
 *
 * <p>宿主只能下「人工」冻结；盘点冻结与退货待确认冻结由对应流程自己下、自己解。
 */
@RestController
public class StockController {

    static final String HOLD = "hold.place";
    static final String RELEASE = "hold.release";

    private final BalanceQueryService balances;
    private final StockHoldService holds;
    private final CommandService commands;

    public StockController(BalanceQueryService balances, StockHoldService holds, CommandService commands) {
        this.balances = balances;
        this.holds = holds;
        this.commands = commands;
    }

    @GetMapping("/api/inventory/v1/balances")
    public List<Balance> balances(@RequestParam(required = false) String locationId,
                                  @RequestParam(required = false) String itemId) {
        return balances.balances(InventoryRequestContext.principal().ownerId(), locationId, itemId).stream()
                .map(Balance::of).toList();
    }

    @PostMapping("/api/inventory/v1/holds")
    public Placed hold(@RequestBody ManualHold req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, HOLD, req, Placed.class, () -> new Placed(holds.place(caller, req.itemId(),
                req.locationId(), null, req.qty(), InvEnums.HoldReason.MANUAL, null, req.note())));
    }

    @PostMapping("/api/inventory/v1/holds/{holdId}/release")
    public Placed release(@PathVariable String holdId) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, RELEASE, holdId, Placed.class, () -> {
            holds.release(caller, holdId);
            return new Placed(holdId);
        });
    }

    public record ManualHold(String itemId, String locationId, BigDecimal qty, String note) {
    }

    public record Placed(String holdId) {
    }

    public record Balance(String itemId, String locationId, BigDecimal onHand, BigDecimal reserved,
                          BigDecimal unavailable, BigDecimal available) {
        static Balance of(InvStockBalance b) {
            BigDecimal onHand = nz(b.getOnHandQuantity());
            BigDecimal reserved = nz(b.getReservedQuantity());
            BigDecimal unavailable = nz(b.getUnavailableQuantity());
            return new Balance(b.getItemId(), b.getLocationId(), onHand, reserved, unavailable,
                    onHand.subtract(reserved).subtract(unavailable));
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v.stripTrailingZeros();
        }
    }
}
