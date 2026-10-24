package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.service.OrderService;
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
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.product.dto.ProductEntitlementBinding;
import ai.neargo.shop.product.service.CardProductBindingService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"shop.member-code.enabled=true",
        "shop.core.entitlement.authorization.enabled=true","shop.pay.entitlement.catalog.enabled=true",
        "shop.pay.entitlement.issue.enabled=true","shop.pay.entitlement.use.enabled=true",
        "shop.pay.paid-card.enabled=true","shop.trade.paid-card-sale.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:card-checkout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PaidCardCheckoutFlowTest {
    @Autowired CardProductBindingService bindings;
    @Autowired ai.neargo.shop.spi.product.CardProductSalePort saleDefinitions;
    @Autowired OrderService orders;
    @Autowired EntitlementManagementAppService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired TokenStores tokens;
    protected JdbcTemplate payJdbc() { return jdbc; }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    @Autowired MemberCodeResolveService members;
    @Autowired EntitlementServiceOrderService services;
    @Autowired EntitlementAuthorizationService consent;
    @Autowired EntitlementServiceUseCoordinator uses;
    @Autowired EntitlementQueryPort history;
    private EntitlementDefinition rule(String title, String model) {
        return new EntitlementDefinition(title, "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, model, "PERIOD_DAILY".equals(model) ? null : 30,
                "PERIOD_DAILY".equals(model) ? 1 : null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("PAID_ORDER"));
    }
    @Autowired ai.neargo.shop.paybridge.CardSaleCoordinator coordinator;
    @Autowired ai.neargo.shop.paybridge.CardSaleEventConsumer events;
    @Autowired ai.neargo.shop.spi.entitlement.PaidCardPaymentGatePort paymentGate;
    @ParameterizedTest
    @ValueSource(strings = {"COUNT", "PERIOD_COUNT", "PERIOD_DAILY"})
    void realCheckoutFreezesFourRightsIssuesOnceAndConsumesViaMemberCode(String model) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "124" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        String buyer = TestLogin.consumer(mvc, json, otp, phone);
        String actor = tokens.of(Realm.CONSUMER).get(buyer).orElseThrow().user().userNo();
        String entity = id(), goods = id(), sku = id(), store = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, actor);
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id(), entity, actor);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,is_default,offline_pay_enabled,"
                + "tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'swimming store','ACTIVE',1,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,entity);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'30 swims','CARD','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,'CN',30000,100,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sku, goods, entity);
        jdbc.update("INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,expire_at,status,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'BUSINESS_LICENSE','Card checkout license',?,"
                + "'VALID','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id(), entity,
                System.currentTimeMillis() + 86400000L);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
        var scope = new CatalogScope("MAIN", entity, actor, false);
        var template = catalog.create(scope, rule("30 swims", model), id());
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
        jdbc.update("UPDATE prd_goods SET on_sale=1,audit_status='APPROVED',"
                + "pay_modes='[\"ONLINE\",\"OFFLINE\"]',fulfillments='[\"STORE_VERIFY\"]' "
                + "WHERE goods_no=?", goods);
        var checkout = new OrderService.CreateOrderCommand(
                List.of(new OrderService.CreateOrderCommand.Item(goods, sku, 2)), "STORE_VERIFY", null,
                null, null, null, "bound card awaiting sale admission", null, "OFFLINE", null, null);
        catalog.policy(scope,template.templateNo(),
                new ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy(0,10L,0L,10,true),id());
        var created=orders.createFor(actor,checkout,id());
        var order=created.payOrderNo();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_card_sale_snapshot WHERE order_no=?",
                Integer.class,order)).isEqualTo(4);
        assertThat(payJdbc().queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,entity)).isZero();
        paymentGate.requireReady("MAIN",order);
        assertThat(catalog.get(scope,template.templateNo()).issuePolicy().heldCount()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order))
                .isEqualTo("WAIT_OFFLINE_PAY");
        String subOrder = jdbc.queryForObject("SELECT sub_order_no FROM ord_sub_order WHERE order_no=?",
                String.class, order);
        var paid = json.readTree(mvc.perform(MockMvcRequestBuilders.post(
                        "/biz/order/" + subOrder + "/confirm-offline-pay")
                .header("Authorization", "Bearer " + merchant))
                .andReturn().getResponse().getContentAsString());
        assertThat(paid.path("code").asInt()).as(paid.toString()).isZero();
        assertThat(jdbc.queryForObject("SELECT pay_channel FROM ord_order WHERE order_no=?", String.class, order))
                .isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT offline_confirmed_by FROM ord_order WHERE order_no=?",
                String.class, order)).isEqualTo(entity);
        assertThat(jdbc.queryForObject("SELECT offline_confirmed_at FROM ord_order WHERE order_no=?",
                Long.class, order)).isPositive();
        var event=new ai.neargo.shop.event.SysOutbox();
        event.setEventType("ORDER_PAID"); event.setAggregateId(order);
        event.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='ORDER_PAID' ORDER BY id DESC LIMIT 1", String.class, order));
        events.consume(event); events.consume(event);
        assertThat(coordinator.recover(ctx,order).issued()).isEqualTo(4);
        assertThat(payJdbc().queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,entity)).isEqualTo(4);
        assertThat(catalog.get(scope,template.templateNo()).issuePolicy().heldCount()).isZero();
        assertThat(catalog.get(scope,template.templateNo()).issuePolicy().issuedCount()).isEqualTo(4);
        String instance = payJdbc().queryForObject("SELECT instance_no FROM ent_instance WHERE entity_no=? ORDER BY id LIMIT 1",
                String.class, entity);
        String serviceGoods = id(), serviceSku = id();
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,"
                + "tenant_no,created_at,updated_at) VALUES(?,?,?,'swim admission','SERVICE',1,'APPROVED',"
                + "'[\"STORE_VERIFY\"]','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", serviceGoods, entity, store);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,"
                + "created_at,updated_at) VALUES(?,?,?,'CN',1000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                serviceSku, serviceGoods, entity);
        var memberCode = members.issue("MAIN", actor);
        var customer = members.resolve(new MemberCodeScope("MAIN", entity, store, actor), memberCode.memberCode());
        var service = services.create(ctx, entity, new ServiceOrderCreate(customer.customerRef(), store,
                List.of(new ServiceOrderCreate.Line(serviceSku, 1))), id());
        var preview = uses.preview(ctx, entity, store, service.serviceOrderNo(), instance);
        assertThat(preview.redeemable()).isTrue();
        // Preparing is not activation: only the committed original service use starts the relative period.
        assertThat(payJdbc().queryForObject("SELECT activation_at FROM ent_instance WHERE instance_no=?",
                java.sql.Timestamp.class, instance)).isNull();
        var authorization = consent.confirm("MAIN", actor, service.serviceOrderNo(),
                new AuthorizationCommand(0, preview.previewRef(), preview.snapshotHash(), id()));
        var use = new ServiceUseSubmit(service.serviceOrderNo(), preview.previewRef(),
                authorization.authorizationRef(), preview.assetRevision());
        var committed = uses.submit(ctx, entity, store, use);
        assertThat(committed.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        assertThat(uses.submit(ctx, entity, store, use)).isEqualTo(committed);
        var detail = history.detail(ctx, new EntitlementQueryPort.DetailQuery(actor, entity, instance, store));
        assertThat(detail.entitlement().status()).isEqualTo(EntitlementQueryPort.AssetState.ACTIVE);
        assertThat(detail.recentUsage()).hasSize(1);
        assertThat(history.usage(ctx, new EntitlementQueryPort.UsageQuery(actor, entity, committed.usageNo()))
                .usage().usageNo()).isEqualTo(committed.usageNo());
        assertThat(json.writeValueAsString(detail)).doesNotContain("principal", "bonus", "authorizationRef", "memberCode");
        var next = services.create(ctx, entity, new ServiceOrderCreate(customer.customerRef(), store,
                List.of(new ServiceOrderCreate.Line(serviceSku, 1))), id());
        var nextPreview = uses.preview(ctx, entity, store, next.serviceOrderNo(), instance);
        if ("PERIOD_DAILY".equals(model)) {
            assertThat(nextPreview.redeemable()).isFalse();
            assertThat(nextPreview.reason()).isEqualTo(EntitlementUsePort.PreviewReason.INSUFFICIENT_UNITS);
            assertThat(payJdbc().queryForObject("SELECT used_units FROM ent_period_bucket WHERE instance_no=?",
                    Integer.class, instance)).isEqualTo(1);
        } else {
            assertThat(detail.entitlement().remainingUnits()).isEqualTo(29);
            assertThat(nextPreview.redeemable()).isTrue();
        }
        assertThat(payJdbc().queryForObject("SELECT COUNT(*) FROM ent_usage WHERE entity_no=?", Integer.class, entity))
                .isEqualTo(1);
        assertThat(catalog.get(scope,template.templateNo()).issuePolicy().issuedCount()).isEqualTo(4);

    }
}
