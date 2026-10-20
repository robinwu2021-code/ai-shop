package ai.neargo.shop.member.service;

import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.dto.MemberCodeViews.CustomerReference;
import ai.neargo.shop.member.dto.MemberCodeViews.MemberCodeView;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;

/** Identification only: callers must never interpret a successful scan as customer spending authorization. */
public interface MemberCodeResolveService {
    MemberCodeView issue(String tenantNo, String userNo);

    CustomerReference resolve(MemberCodeScope scope, String memberCode);

    ResolvedCustomer requireReference(MemberCodeScope scope, String customerRef);
}
