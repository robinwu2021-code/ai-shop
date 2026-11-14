package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.CommandService.Stored;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 按幂等键查一条写命令的结果：回包丢了时先查，而不是盲目重发（TDD §2.12）。 */
@RestController
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @GetMapping("/api/inventory/v1/commands/{requestId}")
    public Stored find(@PathVariable String requestId) {
        return commands.find(InventoryRequestContext.caller(), requestId)
                .orElseThrow(() -> InventoryException.of(InventoryError.NOT_FOUND, "command"));
    }
}
