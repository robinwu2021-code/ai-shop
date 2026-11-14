package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.service.ServicePlatformService.Issued;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台运维接口 · 调用方系统（M8 T8.3）：给多商家宿主签系统凭证。 */
@RestController
@RequestMapping("/api/inventory/admin/v1/systems")
public class AdminSystemController {

    private final ServicePlatformService platform;

    public AdminSystemController(ServicePlatformService platform) {
        this.platform = platform;
    }

    /** 系统凭证：调用时以 {@code X-Inventory-Owner-Ref} 指定本系统的业主；secret 只显示这一次。 */
    @PostMapping("/{systemCode}/credentials")
    public Issued issue(@PathVariable String systemCode, @RequestBody AdminOwnerController.IssueCredential req,
                        @RequestHeader("X-Inventory-Actor") String operator) {
        return platform.issueSystemCredential(systemCode, req.name(), req.capabilities(), req.expiresAt(), operator);
    }
}
