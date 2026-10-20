package ai.neargo.shop.member.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import org.springframework.stereotype.Component;

/** Other owners receive a minimal identity fact, never a ticket entity or the member database. */
@Component
public class MemberCustomerIdentityPortImpl implements CustomerIdentityPort {
    private final MemberCodeResolveService service;

    public MemberCustomerIdentityPortImpl(MemberCodeResolveService service) {
        this.service = service;
    }

    @Override
    public ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query) {
        if (context == null || query == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return service.requireReference(new MemberCodeScope(context.tenantNo(), query.entityNo(), query.storeNo(),
                context.actorNo()), query.customerRef());
    }
}
