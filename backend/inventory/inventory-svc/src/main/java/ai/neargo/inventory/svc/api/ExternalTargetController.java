package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.ExternalTargetService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 外部目标：宿主同步收货方 / 接收人（TDD §2.4、§2.13）。类型取自业态档案。 */
@RestController
public class ExternalTargetController {

    static final String UPSERT = "target.upsert";

    private final ExternalTargetService targets;
    private final CommandService commands;

    public ExternalTargetController(ExternalTargetService targets, CommandService commands) {
        this.targets = targets;
        this.commands = commands;
    }

    @PutMapping("/api/inventory/v1/external-targets/{type}/{ref}")
    public Target upsert(@PathVariable String type, @PathVariable String ref, @RequestBody Sync req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, UPSERT, new Request(type, ref, req), Target.class,
                () -> Target.of(targets.upsert(caller, type, ref, req.name(), req.parentRef(), req.status(),
                        req.sourceVersion() == null ? 0L : req.sourceVersion())));
    }

    @GetMapping("/api/inventory/v1/external-targets")
    public List<Target> list(@RequestParam(required = false) String type,
                             @RequestParam(required = false) String parentRef) {
        return targets.list(InventoryRequestContext.principal().ownerId(), type, parentRef).stream()
                .map(Target::of).toList();
    }

    public record Sync(String name, String parentRef, String status, Long sourceVersion) {
    }

    record Request(String type, String ref, Sync sync) {
    }

    public record Target(String type, String ref, String name, String parentType, String parentRef, String status,
                         Long sourceVersion) {
        static Target of(InvTargetRef t) {
            return new Target(t.getObjectType(), t.getObjectRef(), t.getName(), t.getParentType(), t.getParentRef(),
                    t.getStatus(), t.getSourceVersion());
        }
    }
}
