package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.DocumentQueryService;
import ai.neargo.inventory.domain.service.DocumentQueryService.Document;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.HostInboundService.Draft;
import ai.neargo.inventory.domain.service.HostInboundService.Line;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 入库单（TDD §2.8、§2.13）：建草稿 → 分批追加 → 过账 / 作废。写请求都带幂等键。 */
@RestController
public class InboundController {

    private final HostInboundService inbounds;
    private final DocumentQueryService documents;
    private final CommandService commands;

    public InboundController(HostInboundService inbounds, DocumentQueryService documents, CommandService commands) {
        this.inbounds = inbounds;
        this.documents = documents;
        this.commands = commands;
    }

    @PostMapping("/api/inventory/v1/inbounds")
    public DocNo create(@RequestBody Draft draft) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.create", draft, DocNo.class,
                () -> new DocNo(inbounds.create(caller, draft)));
    }

    @PatchMapping("/api/inventory/v1/inbounds/{no}")
    public DocNo append(@PathVariable String no, @RequestBody Append req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.append", new Keyed(no, req), DocNo.class, () -> {
            inbounds.append(caller, no, req.lines());
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/inbounds/{no}/post")
    public DocNo post(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.post", no, DocNo.class, () -> {
            inbounds.post(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/inbounds/{no}/cancel")
    public DocNo cancel(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.cancel", no, DocNo.class, () -> {
            inbounds.cancel(caller, no);
            return new DocNo(no);
        });
    }

    @GetMapping("/api/inventory/v1/inbounds/{no}")
    public Document view(@PathVariable String no) {
        return documents.inbound(InventoryRequestContext.principal().ownerId(), no);
    }

    public record Append(List<Line> lines) {
    }

    record Keyed(String docNo, Object body) {
    }

    public record DocNo(String docNo) {
    }
}
