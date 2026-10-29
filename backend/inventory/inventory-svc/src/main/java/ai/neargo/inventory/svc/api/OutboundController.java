package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.DocumentQueryService;
import ai.neargo.inventory.domain.service.DocumentQueryService.Document;
import ai.neargo.inventory.domain.service.HostOutboundService;
import ai.neargo.inventory.domain.service.HostOutboundService.Draft;
import ai.neargo.inventory.domain.service.HostOutboundService.Line;
import ai.neargo.inventory.guard.InventoryRequestContext;
import ai.neargo.inventory.svc.api.InboundController.DocNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 出库单（TDD §2.9、§2.13）：处置类出库；扫上级件带出下级件。已过账的不作废。 */
@RestController
public class OutboundController {

    private final HostOutboundService outbounds;
    private final DocumentQueryService documents;
    private final CommandService commands;

    public OutboundController(HostOutboundService outbounds, DocumentQueryService documents,
                              CommandService commands) {
        this.outbounds = outbounds;
        this.documents = documents;
        this.commands = commands;
    }

    @PostMapping("/api/inventory/v1/outbounds")
    public DocNo create(@RequestBody Draft draft) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "outbound.create", draft, DocNo.class,
                () -> new DocNo(outbounds.create(caller, draft)));
    }

    @PatchMapping("/api/inventory/v1/outbounds/{no}")
    public DocNo append(@PathVariable String no, @RequestBody Append req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "outbound.append", new InboundController.Keyed(no, req), DocNo.class, () -> {
            outbounds.append(caller, no, req.lines(), req.serialNos());
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/outbounds/{no}/post")
    public DocNo post(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "outbound.post", no, DocNo.class, () -> {
            outbounds.post(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/outbounds/{no}/cancel")
    public DocNo cancel(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "outbound.cancel", no, DocNo.class, () -> {
            outbounds.cancel(caller, no);
            return new DocNo(no);
        });
    }

    @GetMapping("/api/inventory/v1/outbounds/{no}")
    public Document view(@PathVariable String no) {
        return documents.outbound(InventoryRequestContext.principal().ownerId(), no);
    }

    public record Append(List<Line> lines, List<String> serialNos) {
    }
}
