package ai.neargo.shop.member.service;

import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.dto.MemberCodeViews.CustomerReference;
import ai.neargo.shop.member.dto.MemberCodeViews.MemberCodeView;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;

/** Identification only: callers must never interpret a successful scan as customer spending authorization. */
public interface MemberCodeResolveService {
    /** Only the original MEMBER_CODE ticket can prove the currently authenticated consumer. */
    ResolvedCustomer requireConsumerProof(String tenantNo, String userNo, String memberCode);

    /** Resolve active scoped membership without minting a proof or purchase acceptance. */
    ResolvedCustomer requireCounterReference(MemberCodeScope scope, String memberNo);

    MemberCodeView issue(String tenantNo, String userNo);

    CustomerReference resolve(MemberCodeScope scope, String memberCode);

    /** New guest claims fail closed for legacy references lacking the authenticated actor namespace. */
    ResolvedCustomer requireGuestClaimReference(MemberCodeScope scope, String customerRef);

    ResolvedCustomer requireReference(MemberCodeScope scope, String customerRef);
}
