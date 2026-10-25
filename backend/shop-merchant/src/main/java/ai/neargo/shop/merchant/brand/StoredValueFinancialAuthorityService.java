package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Current resource and financial authority is established in Core before any Pay operation. */
public interface StoredValueFinancialAuthorityService {
 public static final Set<String> FINANCIAL=Set.of("sv.settlement.read","sv.settlement.generate","sv.settlement.reconcile","sv.settlement.dispute","sv.settlement.dispute.resolve","sv.settlement.adjust","sv.settlement.payment.register","sv.settlement.receipt.confirm","sv.settlement.payment.void","sv.settlement.audit","sv.issuer.collection.confirm");

    /** Resolve the same trusted person/account principal used by the current resource actor. */
    String principal(Actor actor);

    /** Resolve report lookup authority without granting unrelated entity financial powers. */
    AuthorizedScope reportLookup(Actor actor,String management,ScopeRef ref,String permission);

    /** Bind the report request to its authorized issuer, currency and resource scope. */
    StoredValueReports.Scope report(Actor actor,String management,View issuer,String permission,String activity,String store);

    /** Require current entity financial grants before permitting issuer lookup. */
    AuthorizedScope financialLookup(Actor actor,String management,ScopeRef ref);

    /** Freeze the caller's current entity permissions and verified evidence references. */
    StoredValueSettlements.Authority financial(Actor actor,String management,
            List<StoredValueSettlements.IssuerGrant> issuers,Set<String> verifiedEvidenceRefs);

    /** Return only explicit live financial permissions on registered entities. */
    Map<String,Set<String>> financialEntities(Actor actor,String management);

    /** List only issuer resources visible under the current entity financial permissions. */
    List<ScopeRef> financeResources(Actor actor,String management,String type);

    /** Restrict all-store reporting to currently owned or explicitly authorized stores. */
    List<ScopeRef> businessStores(Actor actor,String management);

    /** Check the current entity's collection-confirmation permission. */
    boolean canCollect(Actor actor,String management,String entity);

    /** Grant financial capabilities only after live space and entity-owner authorization. */
    Map<String,Object> prepare(Actor actor,String management,String entity);
 ai.neargo.shop.spi.storedvalue.StoredValueCollections.Proof collectionProof(Actor actor,ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope scope,ai.neargo.shop.spi.storedvalue.StoredValueCollections.Requirement requirement);
}
