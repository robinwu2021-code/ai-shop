package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.ActivityAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import java.util.List;

/** Live organization facts and exact policy permissions resolved before remote Pay calls. */
public interface StoredValuePolicyAuthorityService {
    /** Prepare only capabilities justified by the actor’s existing owner authority. */
    void prepareOwnPermissions(Actor actor,String management);

    /** Resolve the exact authorized policy target and currency. */
    PolicyAuthority target(Actor actor,String management,String type,String no,String currency,String permission);

    /** Resolve the current store policy coordinates after checking live authority. */
    StoreScope store(Actor actor,String management,String store,String currency);

    /** Resolve allowed policy catalog targets without widening resource scope. */
    CatalogScope catalog(Actor actor,String management,String type,String no,String currency);

    /** List only targets for which current policy authority can be established. */
    List<PolicyTarget> policyTargets(Actor actor,String management);

    /** Check the requested activity permission against current live store authority. */
    boolean canActivity(Actor actor,String management,String store,String permission);

    /** Resolve activity authority for the exact store, currency and capability. */
    ActivityAuthority activity(Actor actor,String management,String store,String currency,String permission);

    /** Reject decisions without the management-space approval capability. */
    void requireApproval(Actor actor,String management);

    /** Resolve approval authority and current activity scope together. */
    ActivityAuthority approval(Actor actor,String management,String store,String currency);

    /** Recheck current store binding before accepting a previously resolved scope. */
    StoreScope currentStoreScope(StoreScope scope,String entity);

    public record PolicyTarget(String type,String no,String name,boolean canManage) { }
}
