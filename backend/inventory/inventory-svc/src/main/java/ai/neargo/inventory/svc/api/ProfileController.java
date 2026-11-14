package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 宿主读自己业主当前生效的业态档案（只读；改档案走平台运维）。 */
@RestController
public class ProfileController {

    private final BusinessProfileService profiles;

    public ProfileController(BusinessProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/inventory/v1/profile")
    public Versioned current() {
        return profiles.current(InventoryRequestContext.principal().ownerId())
                .orElseThrow(() -> InventoryException.of(InventoryError.NOT_FOUND, "profile"));
    }
}
