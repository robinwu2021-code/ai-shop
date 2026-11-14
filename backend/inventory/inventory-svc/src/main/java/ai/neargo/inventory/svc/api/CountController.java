package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.HostCountService.Posted;
import ai.neargo.inventory.domain.service.HostCountService.Scan;
import ai.neargo.inventory.domain.service.HostCountService.View;
import ai.neargo.inventory.domain.service.HostCountService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import ai.neargo.inventory.svc.api.InboundController.DocNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 盘点（TDD §2.10、§2.13）：开盘冻结 → 扫码 / 录数 → 提交 → 复核 → 过账或作废。 */
@RestController
public class CountController {

    private final HostCountService counts;
    private final CommandService commands;
    private final HostRefs refs;

    public CountController(HostCountService counts, CommandService commands, HostRefs refs) {
        this.counts = counts;
        this.commands = commands;
        this.refs = refs;
    }

    @PostMapping("/api/inventory/v1/counts")
    public DocNo open(@RequestBody Open req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.open", req, DocNo.class,
                () -> new DocNo(counts.open(caller, refs.location(caller.ownerId(), req.locationId(), req.locationRef()),
                        itemIds(caller.ownerId(), req))));
    }

    @GetMapping("/api/inventory/v1/counts/{no}")
    public View view(@PathVariable String no) {
        return counts.view(InventoryRequestContext.principal().ownerId(), no);
    }

    /** 扫码可重复：重扫的 SN 不重计，所以这个入口不要求幂等键也安全，但宿主带上更好查。 */
    @PostMapping("/api/inventory/v1/counts/{no}/scans")
    public List<Scan> scan(@PathVariable String no, @RequestBody ScanRequest req) {
        return counts.scan(InventoryRequestContext.caller(), no, req.serialNos());
    }

    @PutMapping("/api/inventory/v1/counts/{no}/lines/{itemId}")
    public DocNo count(@PathVariable String no, @PathVariable String itemId, @RequestBody Counted req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.line", new InboundController.Keyed(no + "/" + itemId, req),
                DocNo.class, () -> {
                    counts.count(caller, no, itemId, req.countedQty());
                    return new DocNo(no);
                });
    }

    @PostMapping("/api/inventory/v1/counts/{no}/submit")
    public DocNo submit(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.submit", no, DocNo.class, () -> {
            counts.submit(caller, no);
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/counts/{no}/reject")
    public DocNo reject(@PathVariable String no, @RequestBody Note req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.reject", new InboundController.Keyed(no, req), DocNo.class, () -> {
            counts.reject(caller, no, req.note());
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/counts/{no}/approve")
    public DocNo approve(@PathVariable String no, @RequestBody Note req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.approve", new InboundController.Keyed(no, req), DocNo.class, () -> {
            counts.approve(caller, no, req.note());
            return new DocNo(no);
        });
    }

    @PostMapping("/api/inventory/v1/counts/{no}/post")
    public Posted post(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.post", no, Posted.class, () -> counts.post(caller, no));
    }

    @PostMapping("/api/inventory/v1/counts/{no}/cancel")
    public DocNo cancel(@PathVariable String no) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, "count.cancel", no, DocNo.class, () -> {
            counts.cancel(caller, no);
            return new DocNo(no);
        });
    }

    /** 范围物料按内部号（{@code itemIds}）或宿主编号（{@code itemRefs}）给；都不给 = 整个库位 */
    private List<String> itemIds(String ownerId, Open req) {
        if (req.itemRefs() == null || req.itemRefs().isEmpty()) {
            return req.itemIds();
        }
        List<String> ids = new ArrayList<>(req.itemIds() == null ? List.of() : req.itemIds());
        req.itemRefs().forEach(ref -> ids.add(refs.item(ownerId, null, ref)));
        return ids;
    }

    public record Open(String locationId, String locationRef, List<String> itemIds, List<String> itemRefs) {
    }

    public record ScanRequest(List<String> serialNos) {
    }

    public record Counted(int countedQty) {
    }

    public record Note(String note) {
    }
}
