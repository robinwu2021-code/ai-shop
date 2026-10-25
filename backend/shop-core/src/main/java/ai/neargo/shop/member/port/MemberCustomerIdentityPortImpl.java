package ai.neargo.shop.member.port;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import org.springframework.beans.factory.annotation.Autowired;
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
    private TokenStores tokens;

    public MemberCustomerIdentityPortImpl(MemberCodeResolveService service, MerchantOperatorAccessPort operators) {
        this.service = service; this.operators = operators;
    }

    /** New merchant selectors require current session proof or an exact verified bound request. */
    @Autowired
    public MemberCustomerIdentityPortImpl(
            MemberCodeResolveService service,
            MerchantOperatorAccessPort operators,
            TokenStores tokens) {
        this(service, operators);
        this.tokens = tokens;
    }

    @Override
    public ResolvedCustomer resolveCounterReference(
            EntitlementContext context, CounterReferenceQuery query) {
        requireCounterScope(context, query);
        var result =
                service.requireCounterReference(
                        new MemberCodeScope(
                                context.tenantNo(),
                                query.entityNo(),
                                query.storeNo(),
                                context.actorNo()),
                        query.memberNo());
        requireCounterScope(context, query);
        return result;
    }

    private void requireCounterScope(EntitlementContext context, CounterReferenceQuery query) {
        DelegatedIdentityContext.outside();
        if (context == null || query == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var identity = DelegatedIdentityContext.current(context.actorNo());
        if (!"MERCHANT".equals(identity.realm())
                || !identity.tenantNo().equals(context.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var payload =
                new CustomerIdentityInternalApi.CounterReferenceRequest(identity, context, query);
        if (BoundActionContext.present()) {
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    CustomerIdentityInternalApi.COUNTER_ENDPOINT,
                                    "RESOLVE",
                                    identity,
                                    query.entityNo(),
                                    query.storeNo(),
                                    query.memberNo()),
                            payload));
        } else {
            if (tokens == null) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            String token =
                    SecurityUtils.currentToken()
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
            var session =
                    tokens.of(Realm.MERCHANT)
                            .get(token)
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED))
                            .user();
            if (session == null
                    || !identity.tenantNo().equals(session.tenantNo())
                    || !identity.actorNo().equals(session.userNo())
                    || !identity.realm().equals(session.realm().name())
                    || !identity.subjectKind().equals(session.subjectKind().name())) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
        }
        if (!operators.allowed(
                new MerchantOperatorAccessPort.Query(
                        context.tenantNo(),
                        query.entityNo(),
                        query.storeNo(),
                        context.actorNo(),
                        identity.realm(),
                        identity.subjectKind(),
                        BizPerms.CUSTOMER))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
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
