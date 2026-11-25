package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.OpsApiKeyService;
import ai.neargo.shop.platform.OpsApiKeyService.Access;
import ai.neargo.shop.platform.OpsApiKeyService.Issued;
import ai.neargo.shop.platform.OpsApiKeyService.Metadata;
import ai.neargo.shop.platform.OpsApiKeyService.SelfMetadata;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Profile("ops")
@RestController
public class OpsApiKeyController {

    private final OpsApiKeyService keys;

    public OpsApiKeyController(OpsApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/ops/staffs/{staffNo}/api-keys")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_API_KEY_MANAGE + "')")
    public List<Metadata> list(@PathVariable String staffNo) {
        return keys.list(staffNo);
    }

    @PostMapping("/ops/staffs/{staffNo}/api-keys")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_API_KEY_MANAGE + "')")
    public ResponseEntity<Issued> issue(@PathVariable String staffNo, @Valid @RequestBody IssueRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                keys.issue(staffNo, request.keyNo(), request.label(), request.access(), request.validDays()));
    }

    @PostMapping("/ops/staffs/{staffNo}/api-keys/{keyNo}/revoke")
    @PreAuthorize("@perm.can('" + Perms.IAM_STAFF_API_KEY_MANAGE + "')")
    public void revoke(@PathVariable String staffNo, @PathVariable UUID keyNo) {
        keys.revoke(staffNo, keyNo);
    }

    @GetMapping("/ops/staffs/me/api-keys")
    public List<SelfMetadata> listMyApiKeys() {
        long now = System.currentTimeMillis();
        return keys.list(SecurityUtils.currentUserNo()).stream().map(key -> SelfMetadata.of(key, now)).toList();
    }

    @PostMapping("/ops/staffs/me/api-keys")
    public SelfMetadata registerMyApiKey(@Valid @RequestBody RegisterRequest request) {
        return SelfMetadata.of(keys.registerOwn(request.keyNo(), request.label(),
                request.access() == null ? Access.READ_ONLY : request.access(),
                request.validDays(), request.secretHash()), System.currentTimeMillis());
    }

    @PostMapping("/ops/staffs/me/api-keys/{keyNo}/revoke")
    public void revokeMyApiKey(@PathVariable UUID keyNo) {
        keys.revoke(SecurityUtils.currentUserNo(), keyNo);
    }

    public record RegisterRequest(@NotNull UUID keyNo, @NotBlank @Size(max = 80) String label,
                                  Access access, @NotNull @Min(1) @Max(90) Integer validDays,
                                  @NotNull @Pattern(regexp = "[a-f0-9]{64}") String secretHash) {
        @Override
        public String toString() {
            return "RegisterRequest[keyNo=" + keyNo + "]";
        }
    }

    public record IssueRequest(@NotNull UUID keyNo, @NotBlank @Size(max = 80) String label,
                               @NotNull Access access, @NotNull @Min(1) @Max(90) Integer validDays) {}
}
