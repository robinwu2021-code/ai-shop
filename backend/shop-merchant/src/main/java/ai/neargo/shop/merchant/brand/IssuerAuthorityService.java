package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Config;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import java.util.List;
import java.util.Map;

/** Resolves live organization authority before any remote Pay-local operation. */
public interface IssuerAuthorityService {
    /** Resolve the owner-authorized store before issuer settings preparation. */
    StoreAuthority storePreparationScope(Actor actor,String storeNo);

    /** Resolve exact live issuer-read authority before crossing into Pay. */
    AuthorizedScope readScope(Actor actor, String management, String type, String no);

    /** Validate issuer configuration against current resource and settlement authority. */
    AuthorizedScope configureScope(Actor actor, String management, String type, String no, Config config);

    /** Verify every source store before migration work is admitted. */
    void verifyMigrationSources(List<StoreAuthority> sources);

    /** Prepare settings only from the original owner-authorized store scope. */
    StoreAuthority prepareSettings(Actor actor,String management,String store);

    /** Return operating entities that current authority permits for issuer settlement. */
    List<Map<String,Object>> settlementCandidates(Actor actor,String management,String type,String no);
}
