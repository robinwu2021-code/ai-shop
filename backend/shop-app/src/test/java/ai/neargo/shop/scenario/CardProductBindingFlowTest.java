package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.product.dto.CardProductBindingView;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.service.CardProductBindingService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:card-binding;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class CardProductBindingFlowTest {
    @Autowired CardProductBindingService bindings;
    @Autowired EntitlementCatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired TokenStores tokens;
    private String id() { return UUID.randomUUID().toString(); }
    private EntitlementDefinition rule(String title) {
        return new EntitlementDefinition(title, "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("PAID_ORDER"));
    }
    @Test void actualPublishedCardBindingKeepsSkuPriceAndOriginalReplyAcrossLaterChanges() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "124" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        String buyer = TestLogin.consumer(mvc, json, otp, phone);
        String actor = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        String entity = id(), goods = id(), sku = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, actor);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(), entity, actor);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'30 swims','CARD','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,'CN',30000,100,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sku, goods, entity);
        var scope = new CatalogScope("MAIN", entity, actor, false);
        var template = catalog.create(scope, rule("30 swims"), id());
        var v1 = catalog.publish(scope, template.templateNo(), template.revision(), id());
        var ctx = new EntitlementContext("MAIN", actor, "BIND", "a".repeat(64));
        String key = id();
        var c = new ProductEntitlementBinding(goods, sku, 0, v1.versionNo(), 2);
        var response = json.readTree(mvc.perform(MockMvcRequestBuilders.post("/biz/card-product-bindings")
                .header("Authorization", "Bearer " + merchant).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(c)))
                .andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).isZero();
        assertThat(response.path("data").isObject()).isTrue();
        var first = json.readValue(response.path("data").toString(), CardProductBindingView.class);
        assertThat(first.templateVersionNo()).isEqualTo(v1.versionNo());
        assertThat(first.productRevision()).isEqualTo(1);
        assertThat(first.unitsPerItem()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT price FROM prd_sku WHERE sku_no=?", Long.class, sku)).isEqualTo(30000);
        var head = catalog.get(scope, template.templateNo());
        var draft = catalog.saveDraft(scope, template.templateNo(), head.revision(), rule("New card title"), id());
        var v2 = catalog.publish(scope, template.templateNo(), draft.revision(), id());
        assertThat(jdbc.queryForObject("SELECT template_version_no FROM prd_entitlement_binding WHERE sku_no=?",
                String.class, sku)).isEqualTo(v1.versionNo());
        assertThat(jdbc.queryForObject("SELECT rule_snapshot FROM prd_entitlement_binding WHERE sku_no=?",
                String.class, sku)).contains("30 swims").doesNotContain("New card title");
        var second = bindings.bind(ctx, entity, new ProductEntitlementBinding(goods, sku, 1, v2.versionNo(), 1), id());
        assertThat(second.productRevision()).isEqualTo(2);
        assertThat(bindings.bind(ctx, entity, c, key)).isEqualTo(first);
        assertThatThrownBy(() -> bindings.bind(ctx, entity,
                new ProductEntitlementBinding(goods, sku, 1, v2.versionNo(), 1), key)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> bindings.bind(ctx, entity, c, id())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> bindings.bind(ctx, "OTHER", c, id())).isInstanceOf(BizException.class);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = List.<Callable<Boolean>>of(
                    () -> attempt(ctx, entity, new ProductEntitlementBinding(goods, sku, 2, v2.versionNo(), 3)),
                    () -> attempt(ctx, entity, new ProductEntitlementBinding(goods, sku, 2, v2.versionNo(), 4)));
            int won = 0;
            for (var result : pool.invokeAll(attempts)) if (result.get()) won++;
            assertThat(won).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM prd_goods WHERE goods_no=?", Long.class, goods)).isEqualTo(3);
        jdbc.update("UPDATE prd_goods SET type='SERVICE' WHERE goods_no=?", goods);
        assertThatThrownBy(() -> bindings.bind(ctx, entity,
                new ProductEntitlementBinding(goods, sku, 3, v2.versionNo(), 1), id())).isInstanceOf(BizException.class);
    }
    private boolean attempt(EntitlementContext ctx, String entity, ProductEntitlementBinding c) {
        try { bindings.bind(ctx, entity, c, id()); return true; }
        catch (BizException conflict) { return false; }
    }
}
