package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.SerialService;
import ai.neargo.inventory.domain.service.SerialService.InStock;
import ai.neargo.inventory.guard.InventoryRequestContext;
import ai.neargo.inventory.svc.api.InboundController.DocNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 在仓 SN、拆装（SERIAL 能力包）与流水（TDD §2.6、§2.11、§2.13）。 */
@RestController
public class SerialController {

    private final SerialService serials;
    private final CommandService commands;

    public SerialController(SerialService serials, CommandService commands) {
        this.serials = serials;
        this.commands = commands;
    }

    @GetMapping("/api/inventory/v1/serials")
    public List<InStock> inStock(@RequestParam(required = false) String itemId,
                                 @RequestParam(required = false) String locationId,
                                 @RequestParam(required = false) String parentSerialNo) {
        return serials.inStock(InventoryRequestContext.principal().ownerId(), itemId, locationId, parentSerialNo);
    }

    @PostMapping("/api/inventory/v1/serials/{serialNo}/assemble")
    public DocNo assemble(@PathVariable String serialNo, @RequestBody Assemble req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "serial.assemble", new InboundController.Keyed(serialNo, req), DocNo.class,
                () -> {
                    serials.assemble(caller, serialNo, req.parentSerialNo());
                    return new DocNo(serialNo);
                });
    }

    @PostMapping("/api/inventory/v1/serials/{serialNo}/disassemble")
    public DocNo disassemble(@PathVariable String serialNo) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "serial.disassemble", serialNo, DocNo.class, () -> {
            serials.disassemble(caller, serialNo);
            return new DocNo(serialNo);
        });
    }

    @GetMapping("/api/inventory/v1/ledger")
    public List<Entry> ledger(@RequestParam(required = false) String itemId,
                              @RequestParam(required = false) String locationId,
                              @RequestParam(required = false) String docNo,
                              @RequestParam(required = false) Long afterId,
                              @RequestParam(defaultValue = "100") int limit) {
        return serials.ledger(InventoryRequestContext.principal().ownerId(), itemId, locationId, docNo, afterId, limit)
                .stream().map(Entry::of).toList();
    }

    public record Assemble(String parentSerialNo) {
    }

    public record Entry(Long id, String itemId, String locationId, String docKind, String docNo, Integer lineNo,
                        String reasonCode, BigDecimal qtyDelta, BigDecimal balanceAfter, LocalDateTime occurredAt,
                        String operator) {
        static Entry of(InvLedger l) {
            return new Entry(l.getId(), l.getItemId(), l.getLocationId(), l.getDocKind(), l.getDocNo(), l.getLineNo(),
                    l.getReasonCode(), l.getQtyDeltaQuantity(), l.getBalanceAfterQuantity(), l.getOccurredAt(),
                    l.getOperator());
        }
    }
}
