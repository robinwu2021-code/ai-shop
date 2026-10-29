package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.service.ServicePlatformService.Issued;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 平台运维：开通业主、签发服务凭证、装业态档案、翻转写入方（TDD §2.13「平台运维」层）。
 *
 * <p>只认运维令牌（{@code AdminTokenFilter}），宿主的服务凭证进不来；档案宿主只读（TDD §7.2 Q4）。
 */
@RestController
@RequestMapping("/api/inventory/admin/v1/owners")
public class AdminOwnerController {

    private final ServicePlatformService platform;
    private final BusinessProfileService profiles;

    public AdminOwnerController(ServicePlatformService platform, BusinessProfileService profiles) {
        this.platform = platform;
        this.profiles = profiles;
    }

    @PostMapping
    public Map<String, String> open(@RequestBody OpenOwner req, @RequestHeader("X-Inventory-Actor") String operator) {
        return Map.of("ownerId", platform.openOwner(req.systemCode(), req.externalRef(), req.name(), operator));
    }

    @PostMapping("/{ownerId}/credentials")
    public Issued issue(@PathVariable String ownerId, @RequestBody IssueCredential req,
                        @RequestHeader("X-Inventory-Actor") String operator) {
        return platform.issueCredential(ownerId, req.name(), req.capabilities(), req.expiresAt(), operator);
    }

    @PutMapping("/{ownerId}/profile")
    public Map<String, Integer> profile(@PathVariable String ownerId, @RequestBody SaveProfile req,
                                        @RequestHeader("X-Inventory-Actor") String operator) {
        return Map.of("version", profiles.save(ownerId, req.profile(), req.reason(), operator));
    }

    @PutMapping("/{ownerId}/write-authority")
    public void writeAuthority(@PathVariable String ownerId, @RequestBody Map<String, String> req,
                               @RequestHeader("X-Inventory-Actor") String operator) {
        platform.setWriteAuthority(ownerId, req.get("writeAuthority"), operator);
    }

    public record OpenOwner(String systemCode, String externalRef, String name) {
    }

    public record IssueCredential(String name, Set<Capability> capabilities, LocalDateTime expiresAt) {
    }

    public record SaveProfile(BusinessProfile profile, String reason) {
    }
}
