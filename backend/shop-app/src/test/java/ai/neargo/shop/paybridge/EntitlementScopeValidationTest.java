package ai.neargo.shop.paybridge;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** New catalog scope checks query owner domains with explicit tenant and never trust public actor/owner fields. */
@SpringBootTest
@ActiveProfiles("test")
class EntitlementScopeValidationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementScopeValidationService validation;
    @Autowired private EntitlementScopeResolver resolver;
    private String entity;
    private String store;
    private String goods;
    private String category;
    private String otherStore;
    private final List<String> extraGoods = new ArrayList<>();

    @BeforeEach
    void fixture() {
        entity = "SCOPE-M-" + UUID.randomUUID();
        store = "SCOPE-S-" + UUID.randomUUID();
        goods = "SCOPE-G-" + UUID.randomUUID();
        category = "SCOPE-C-" + UUID.randomUUID();
        otherStore = "SCOPE-S-" + UUID.randomUUID();
        jdbc.update("INSERT INTO mch_entity(entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'scope merchant','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'scope store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'unassigned store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", otherStore, entity);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'scope goods','SERVICE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_category(category_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'platform category','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", category);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        BizContext.clear();
        for (String id : extraGoods) {
            jdbc.update("DELETE FROM prd_goods WHERE goods_no=?", id);
        }
        jdbc.update("DELETE FROM prd_goods WHERE goods_no=?", goods);
        jdbc.update("DELETE FROM prd_category WHERE category_no=?", category);
        jdbc.update("DELETE FROM mch_store WHERE store_no=?", store);
        jdbc.update("DELETE FROM mch_store WHERE store_no=?", otherStore);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no=?", entity);
    }

    @Test
    void acceptsOwnedStoreGoodsAndPlatformCategoryWithoutInventingCategoryOwner() {
        validation.validate("MAIN", entity, scope("ALL"));
        validation.validate("MAIN", entity, scope("STORES", store));
        validation.validate("MAIN", entity, scope("GOODS", goods));
        validation.validate("MAIN", entity, scope("CATEGORIES", category));
        jdbc.update("UPDATE mch_store SET status='READONLY' WHERE store_no=?", store);
        validation.validate("MAIN", entity, scope("STORES", store));
    }

    @Test
    void rejectsCrossEntityAndCrossTenantFactsEvenWhenBusinessNumbersExist() {
        assertThatThrownBy(() -> validation.validate("MAIN", "OTHER", scope("STORES", store)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_goods SET entity_no='OTHER' WHERE goods_no=?", goods);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("GOODS", goods)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_goods SET entity_no=?,tenant_no='OTHER' WHERE goods_no=?", entity, goods);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("GOODS", goods)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE mch_store SET entity_no='OTHER' WHERE store_no=?", store);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("STORES", store)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE mch_store SET entity_no=?,tenant_no='OTHER' WHERE store_no=?", entity, store);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("STORES", store)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_category SET tenant_no='OTHER' WHERE category_no=?", category);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("CATEGORIES", category)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsMissingInactiveDeletedAndMalformedScope() {
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("GOODS", "missing")))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_goods SET deleted=1 WHERE goods_no=?", goods);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("GOODS", goods)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_category SET status='ARCHIVED' WHERE category_no=?", category);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("CATEGORIES", category)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("ALL", store)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("STORES", store, store)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE mch_store SET status='SUSPENDED' WHERE store_no=?", store);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("STORES", store)))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE mch_entity SET status='SUSPENDED' WHERE entity_no=?", entity);
        assertThatThrownBy(() -> validation.validate("MAIN", entity, scope("ALL")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void trustedMerchantScopeUsesAuthenticatedIdentityAndNeverElevatesPlatformActor() {
        login(LoginUser.merchant("STAFF-A", "test"), true, Set.of());
        var result = resolver.resolve(scope("STORES", store));
        assertThat(result.tenantNo()).isEqualTo("MAIN");
        assertThat(result.entityNo()).isEqualTo(entity);
        assertThat(result.actorNo()).isEqualTo("STAFF-A");
        assertThat(result.platformActor()).isFalse();
        login(LoginUser.consumer("STAFF-A", "test"), true, Set.of());
        assertThatThrownBy(() -> resolver.resolve(scope("ALL"))).isInstanceOf(BizException.class);
    }

    @Test
    void limitedStaffCannotConfigureOtherStoresOrAllMerchantStores() {
        login(LoginUser.merchant("STAFF-A", "test"), false, Set.of(store));
        resolver.resolve(scope("STORES", store));
        assertThatThrownBy(() -> resolver.resolve(scope("ALL"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> resolver.resolve(scope("STORES", otherStore))).isInstanceOf(BizException.class);
    }

    @Test
    void giftGoodsMustBelongToTheSameMerchantEvenWithAllStoreScope() {
        login(LoginUser.merchant("STAFF-A", "test"), true, Set.of());
        var definition = new EntitlementDefinition("gift", "EXCHANGE", "MERCHANT", "CNY",
                "GIFT", null, null, goods, null, null, "COUNT", 1, null,
                null, scope("ALL"), List.of("MANUAL"));
        assertThat(resolver.resolveDefinition(definition).entityNo()).isEqualTo(entity);
        jdbc.update("UPDATE prd_goods SET entity_no='OTHER' WHERE goods_no=?", goods);
        assertThatThrownBy(() -> resolver.resolveDefinition(definition)).isInstanceOf(BizException.class);
    }

    @Test
    void validatesEveryBatchIncludingTheLastReference() {
        for (int i = 0; i < 201; i++) {
            String id = "BATCH-G-" + UUID.randomUUID();
            extraGoods.add(id);
            jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,tenant_no,created_at,updated_at) "
                    + "VALUES (?,?,'batch goods','SERVICE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, entity);
        }
        validation.validate("MAIN", entity, new RedemptionScope("GOODS", extraGoods));
        jdbc.update("UPDATE prd_goods SET entity_no='OTHER' WHERE goods_no=?", extraGoods.getLast());
        assertThatThrownBy(() -> validation.validate("MAIN", entity, new RedemptionScope("GOODS", extraGoods)))
                .isInstanceOf(BizException.class);
    }

    private void login(LoginUser user, boolean owner, Set<String> allowedStores) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        BizContext.set(new BizContext(entity, Set.of(), Set.of(), allowedStores, store, owner));
    }

    private RedemptionScope scope(String mode, String... refs) {
        return new RedemptionScope(mode, List.of(refs));
    }
}
