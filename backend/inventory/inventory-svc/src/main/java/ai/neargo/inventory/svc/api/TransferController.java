package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.HostOutboundService;
import ai.neargo.inventory.domain.service.HostTransferService.View;
import ai.neargo.inventory.domain.service.HostTransferService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import ai.neargo.inventory.svc.api.InboundController.DocNo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 宿主接口的账内调拨（TDD §2.21）：建单 → 发运（进在途）→ 收货；草稿可作废。
 * SN 物料扫件即可，扫上级件带出下级件；跨账本发货不走这里（见出库对象的一出一入）。
 */
@RestController
public class TransferController {

    private static final String BASE = "/api/inventory/v1/transfers";

    private final HostTransferService transfers;
    private final CommandService commands;
    private final HostRefs refs;

    public TransferController(HostTransferService transfers, CommandService commands, HostRefs refs) {
        this.transfers = transfers;
        this.commands = commands;
        this.refs = refs;
    }

    @PostMapping(BASE)
    public DocNo create(@Validated @RequestBody Create req) {
        Caller caller = InventoryRequestContext.caller();
        String owner = caller.ownerId();
        return commands.execute(caller, "transfer.create", req, DocNo.class, () -> new DocNo(transfers.create(caller,
                new HostTransferService.Draft(refs.location(owner, req.fromLocationId(), req.fromLocationRef()),
                        refs.location(owner, req.toLocationId(), req.toLocationRef()),
                        req.lines() == null ? List.of() : req.lines().stream()
                                .map(l -> new HostOutboundService.Line(refs.item(owner, l.itemId(), l.itemRef()),
                                        l.qty())).toList(),
                        req.serialNos(), req.remark()))));
    }

    @GetMapping(BASE + "/{no}")
    public View view(@PathVariable String no) {
        return transfers.get(InventoryRequestContext.principal().ownerId(), no);
    }

    @PostMapping(BASE + "/{no}/ship")
    public DocNo ship(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "transfer.ship", no, DocNo.class, () -> {
            transfers.ship(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping(BASE + "/{no}/receive")
    public DocNo receive(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "transfer.receive", no, DocNo.class, () -> {
            transfers.receive(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping(BASE + "/{no}/cancel")
    public DocNo cancel(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "transfer.cancel", no, DocNo.class, () -> {
            transfers.cancel(caller, no);
            return new DocNo(no);
        });
    }

    /** 库位、物料都可以给内部号或宿主编号（TDD §2.22） */
    public record Create(String fromLocationId, String fromLocationRef, String toLocationId, String toLocationRef,
                         @Size(max = 500) List<@Valid QtyLine> lines, @Size(max = 2000) List<String> serialNos,
                         @Size(max = 255) String remark) {
    }

    public record QtyLine(String itemId, String itemRef, @NotNull @Positive Integer qty) {
    }
}
