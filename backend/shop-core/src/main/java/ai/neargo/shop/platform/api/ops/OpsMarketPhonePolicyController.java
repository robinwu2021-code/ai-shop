package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("ops")
@RestController
public class OpsMarketPhonePolicyController {
    private final MarketPhonePolicyService policies;
    private final AuditLogPort audit;
    public OpsMarketPhonePolicyController(MarketPhonePolicyService policies, AuditLogPort audit) {this.policies=policies;this.audit=audit;}
    @GetMapping("/ops/market-phone-policies")
    @PreAuthorize("@perm.can('system:param:read') or @perm.can('merchant:apply:onbehalf')")
    public List<MarketPhonePolicyService.Policy> list() { return policies.list(); }
    @PostMapping("/ops/market-phone-policies/{market}")
    @PreAuthorize("@perm.can('system:param:update')")
    public MarketPhonePolicyService.Policy save(@PathVariable String market, @Valid @RequestBody Save command) {
        var result=policies.save(market,command.recordOnly(),command.smsVerificationEnabled(),SecurityUtils.currentUserNo());
        audit.record("MARKET_PHONE_POLICY",market,command.reason());return result;
    }
    public record Save(@NotNull Boolean recordOnly,@NotNull Boolean smsVerificationEnabled,@NotBlank @Size(max=1024) String reason) {}
}
