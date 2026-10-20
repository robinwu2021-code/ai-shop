package ai.neargo.shop.member.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Core resolves merchant-owned segments; pay persists this exact result rather than accepting recipient IDs. */
@Component
public class EntitlementIssueAudiencePortImpl implements EntitlementIssueAudiencePort {
    private final MemberQueryPort members;
    private final MerchantScopeFactsPort merchants;
    private final UserScopeFactsPort users;
    public EntitlementIssueAudiencePortImpl(MemberQueryPort members, MerchantScopeFactsPort merchants,
                                            UserScopeFactsPort users) {
        this.members = members; this.merchants = merchants; this.users = users;
    }
    @Override public AudienceSnapshot resolve(EntitlementContext context, String entity, String reference) {
        // Existing member audience resolution is MAIN-only; do not infer tenant from a segment label.
        if (!"MAIN".equals(context.tenantNo()) || !merchants.entityActive(context.tenantNo(), entity)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var resolved = members.resolveSegment(entity, reference);
        List<String> ids = resolved.reachable().stream().map(MemberQueryPort.Audience::userNo).distinct().toList();
        if (ids.size() > 1000) throw BizException.of(ErrorCode.BAD_REQUEST);
        var recipients = ids.stream().map(user -> users.findActive(context.tenantNo(), user).isPresent()
                ? new Recipient(user, true, null) : new Recipient(user, false, "ACCOUNT_UNAVAILABLE")).toList();
        return new AudienceSnapshot(context.tenantNo(), entity, reference, recipients);
    }
}
