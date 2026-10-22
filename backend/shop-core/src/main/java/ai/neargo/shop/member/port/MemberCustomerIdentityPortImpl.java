package ai.neargo.shop.member.port;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
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

    private final MerchantOperatorAccessPort operators;
    public MemberCustomerIdentityPortImpl(MemberCodeResolveService service, MerchantOperatorAccessPort operators) {
        this.service = service; this.operators = operators;
    }

    @Override
    public ResolvedCustomer resolveClaimReference(EntitlementContext context, ReferenceQuery query) {
        if (context == null || query == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var current = SecurityUtils.requireUser();
        if (!"MERCHANT".equals(current.realm().name()) || !current.tenantNo().equals(context.tenantNo())
                || !current.userNo().equals(context.actorNo())
                || !("MCH".equals(current.subjectKind().name()) || "USR".equals(current.subjectKind().name()))
                || !operators.allowed(new MerchantOperatorAccessPort.Query(context.tenantNo(), query.entityNo(),
                        query.storeNo(), context.actorNo(), current.realm().name(), current.subjectKind().name(),
                        BizPerms.CUSTOMER))) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return service.requireGuestClaimReference(new MemberCodeScope(context.tenantNo(), query.entityNo(),
                query.storeNo(), context.actorNo()), query.customerRef());
    }

    @Override
    public ResolvedCustomer verifyConsumerProof(EntitlementContext context, String memberCode) {
        if (context == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return service.requireConsumerProof(context.tenantNo(), context.actorNo(), memberCode);
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
