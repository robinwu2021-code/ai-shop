package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;

import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView;

import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair;

import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize;

import ai.neargo.shop.pay.migration.entity.EntMigrationStage;

import java.time.Instant;

import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** Domain contract; transactional behavior remains in the implementation. */
public interface EntitlementMigrationAliasService {
    LegacyCouponAliasComparator.Comparison compare(CatalogScope scope,Pair pair);
    AliasView recognize(CatalogScope scope,Recognize command,String key);
    AliasView get(CatalogScope scope,String no);
    Optional<LegacyCouponMigrationNormalizer.Report> assessRecognized(CatalogScope scope,EntMigrationStage requested,JsonNode current,Instant at);
}
