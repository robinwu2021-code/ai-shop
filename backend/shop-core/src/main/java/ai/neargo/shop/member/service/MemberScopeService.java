package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.MemberOperatingScopePort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;

/** Membership operations preserve canonical identity and the current store or brand scope. */
public interface MemberScopeService {
    /** Resolve the current canonical operating scope and reject inconsistent ownership. */
    MemberOperatingScopePort.Scope scope(String tenant,String entity,String store);

    /** Require consistent canonical customer identity before membership work. */
    MemberIdentityFactsPort.Identity identity(String tenant,String customer);

    /** Enroll only the canonical customer under the locked current operating scope. */
    MemberScopeModels.ScopeMember enroll(String tenant,String entity,String store,String customer,String actor,String remark);

    /** Reject access unless the current scope or membership satisfies the original rules. */
    MemberScopeModels.ScopeMember require(String tenant,String entity,String store,String no);

    /** Link a legacy member only from explicit matching customer and source-store evidence. */
    void linkLegacy(String tenant,String legacyNo,String memberNo,String sourceStore,String actor);

    /** Activate only memberships whose canonical customer has a verified user identity. */
    void claim(String tenant,String customer);

    /** Read the source store name only within the currently resolved operating scope. */
    String sourceName(String tenant,String entity,String store,String source);

    /** Resolve the customer’s membership inside the current operating scope. */
    MemberScopeModels.ScopeMember byCustomer(String tenant,String entity,String store,String customer);

    /** Import only legacy memberships backed by explicit verified source-store evidence. */
    long importLegacy(String tenant,String entity,String store,String actor);
}
