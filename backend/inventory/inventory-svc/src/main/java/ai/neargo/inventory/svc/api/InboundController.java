package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.DocumentQueryService.Document;
import ai.neargo.inventory.domain.service.DocumentQueryService;
import ai.neargo.inventory.domain.service.HostInboundService.Draft;
import ai.neargo.inventory.domain.service.HostInboundService.Line;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.PairedTransferService.Received;
import ai.neargo.inventory.domain.service.PairedTransferService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
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
    private final PairedTransferService paired;
    private final HostRefs refs;

    public InboundController(HostInboundService inbounds, DocumentQueryService documents, CommandService commands,
                             PairedTransferService paired, HostRefs refs) {
        this.inbounds = inbounds;
        this.documents = documents;
        this.commands = commands;
        this.paired = paired;
        this.refs = refs;
    }

    @PostMapping("/api/inventory/v1/inbounds")
    public DocNo create(@RequestBody Create req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.create", req, DocNo.class,
                () -> new DocNo(inbounds.create(caller, new Draft(
                        refs.location(caller.ownerId(), req.locationId(), req.locationRef()), req.purpose(),
                        req.sourceRef(), req.basis(), req.attachmentRefs(), req.remark(),
                        lines(caller.ownerId(), req.lines())))));
    }

    @PatchMapping("/api/inventory/v1/inbounds/{no}")
    public DocNo append(@PathVariable String no, @RequestBody Append req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.append", new Keyed(no, req), DocNo.class, () -> {
            inbounds.append(caller, no, lines(caller.ownerId(), req.lines()));
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/inbounds/{no}/post")
    public DocNo post(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.post", no, DocNo.class, () -> {
            requireUnpaired(caller, no);
            inbounds.post(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/inbounds/{no}/cancel")
    public DocNo cancel(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.cancel", no, DocNo.class, () -> {
            requireUnpaired(caller, no);
            inbounds.cancel(caller, no);
            return new DocNo(no);
        });
    }

    /** 退货业务确认：宿主解除了本地业务关联，解冻这张退货入库的「退货待确认」（AC-10）。 */
    @PostMapping("/api/inventory/v1/inbounds/{no}/business-clearance")
    public DocNo businessClearance(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.clearance", no, DocNo.class, () -> {
            inbounds.businessClearance(caller, no);
            return new DocNo(no);
        });
    }

    /** 成对入库单确认收货（TDD §2.19）：按实收入账，差异回传发货方。 */
    @PostMapping("/api/inventory/v1/inbounds/{no}/receive")
    public Receipt receive(@PathVariable String no, @Validated @RequestBody Receive req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "inbound.receive", new Keyed(no, req), Receipt.class,
                () -> new Receipt(no, paired.receive(caller, no, req.lines().stream()
                        .map(l -> new Received(refs.item(caller.ownerId(), l.itemId(), l.itemRef()), l.qty()))
                        .toList())));
    }

    @GetMapping("/api/inventory/v1/inbounds/{no}")
    public Document view(@PathVariable String no) {
        return documents.inbound(InventoryRequestContext.principal().ownerId(), no);
    }

    /** 成对入库单只能确认收货：直接过账会绕过差异回传，作废会让发货方的货凭空消失 */
    private void requireUnpaired(Caller caller, String inboundNo) {
        if (paired.pairedInbound(caller.ownerId(), inboundNo)) {
            throw InventoryException.of(InventoryError.CONFLICT, "paired_use_receive");
        }
    }

    private List<Line> lines(String ownerId, List<InLine> lines) {
        return lines == null ? List.of() : lines.stream().map(l -> new Line(
                refs.item(ownerId, l.itemId(), l.itemRef()), l.qty(), l.gateRef(), l.serials())).toList();
    }

    /** 库位、物料都可以给内部号或宿主编号（TDD §2.22） */
    public record Create(String locationId, String locationRef, String purpose, String sourceRef, String basis,
                         List<String> attachmentRefs, String remark, List<InLine> lines) {
    }

    public record InLine(String itemId, String itemRef, Integer qty, String gateRef,
                         List<HostInboundService.Serial> serials) {
    }

    public record Append(List<InLine> lines) {
    }

    public record Receive(@NotNull @Size(max = 500) List<@Valid ReceivedLine> lines) {
    }

    /** 物料给收货方账本的内部号，或宿主编号（与发货方同一个） */
    public record ReceivedLine(String itemId, String itemRef, @NotNull @PositiveOrZero Integer qty) {
    }

    public record Receipt(String docNo, String pairNo) {
    }

    record Keyed(String docNo, Object body) {
    }

    public record DocNo(String docNo) {
    }
}
