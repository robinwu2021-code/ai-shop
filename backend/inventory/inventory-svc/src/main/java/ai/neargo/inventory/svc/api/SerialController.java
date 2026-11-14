package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.SerialService.InStock;
import ai.neargo.inventory.domain.service.SerialService;
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
import java.util.Map;
import java.util.stream.Collectors;

/** 在仓 SN、拆装（SERIAL 能力包）与流水（TDD §2.6、§2.11、§2.13）。 */
@RestController
public class SerialController {

    private final SerialService serials;
    private final CommandService commands;
    private final HostRefs refs;
    private final HostReferenceService hostRefs;

    public SerialController(SerialService serials, CommandService commands, HostRefs refs,
                            HostReferenceService hostRefs) {
        this.serials = serials;
        this.commands = commands;
        this.refs = refs;
        this.hostRefs = hostRefs;
    }

    /** 物料、库位可按内部号或宿主编号过滤；响应两种都带（TDD §2.22） */
    @GetMapping("/api/inventory/v1/serials")
    public List<Serial> inStock(@RequestParam(required = false) String itemId,
                                @RequestParam(required = false) String itemRef,
                                @RequestParam(required = false) String locationId,
                                @RequestParam(required = false) String locationRef,
                                @RequestParam(required = false) String parentSerialNo) {
        String owner = InventoryRequestContext.principal().ownerId();
        List<InStock> rows = serials.inStock(owner, refs.optionalItem(owner, itemId, itemRef),
                refs.optionalLocation(owner, locationId, locationRef), parentSerialNo);
        Map<String, String> items = hostRefs.itemRefsOf(owner,
                rows.stream().map(InStock::itemId).collect(Collectors.toSet()));
        Map<String, String> locations = hostRefs.locationRefsOf(owner,
                rows.stream().map(InStock::locationId).collect(Collectors.toSet()));
        return rows.stream().map(r -> new Serial(r.serialNo(), r.itemId(), items.get(r.itemId()), r.locationId(),
                locations.get(r.locationId()), r.parentSerialNo(), r.episodeId(), r.gateRef(), r.holdReasons()))
                .toList();
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
                              @RequestParam(required = false) String itemRef,
                              @RequestParam(required = false) String locationId,
                              @RequestParam(required = false) String locationRef,
                              @RequestParam(required = false) String docNo,
                              @RequestParam(required = false) Long afterId,
                              @RequestParam(defaultValue = "100") int limit) {
        String owner = InventoryRequestContext.principal().ownerId();
        return serials.ledger(owner, refs.optionalItem(owner, itemId, itemRef),
                        refs.optionalLocation(owner, locationId, locationRef), docNo, afterId, limit)
                .stream().map(Entry::of).toList();
    }

    public record Assemble(String parentSerialNo) {
    }

    public record Serial(String serialNo, String itemId, String itemRef, String locationId, String locationRef,
                         String parentSerialNo, String episodeId, String gateRef, List<String> holdReasons) {
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
