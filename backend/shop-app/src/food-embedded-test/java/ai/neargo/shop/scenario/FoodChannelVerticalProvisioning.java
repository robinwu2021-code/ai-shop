package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.commerce.dto.AuthorizeChannelAccountReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogPreviewVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.SetStatus;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.service.CatalogPublicationService;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.commerce.service.ChannelAccountService;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.commerce.service.SyncRunService;
import ai.neargo.shop.commerce.service.impl.ChannelInboundServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOrderImportService;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService.SaveReq;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions the native vertical entirely through original FOOD/CIM/core services, actual
 * independent Inventory posting and persistent external Mock HTTP. Published terminal resource
 * identities, original merchant tax and market currency supply every supplier invoice field; no CIM
 * rows, owner facts, identity proofs, mappings or funding receipts are inserted by the fixture.
 */
final class FoodChannelVerticalProvisioning {
    record ExternalStore(String accountRef, String storeRef) {}

    record Published(ChannelConnectionVO connection, CatalogPreviewVO catalog, String runNo) {}

    private final ApplicationContext context;
    private final ObjectMapper json;
    private final MockCommercePlatform platform;
    private final MockCommerceGateway gateway;
    private final String token;

    FoodChannelVerticalProvisioning(ApplicationContext context, String token) {
        this.context = context;
        json = context.getBean(ObjectMapper.class);
        platform = context.getBean(MockCommercePlatform.class);
        gateway = context.getBean(MockCommerceGateway.class);
        this.token = token;
    }

    ExternalStore externalStore(String entity, String store) throws Exception {
        var external = new ExternalStore("vertical-account-" + key(), "vertical-store-" + key());
        var definition = gateway.registration().definition();
        post(
                "/mock/v1/admin/fixtures",
                new MockCommercePlatform.Fixture(
                        entity,
                        store,
                        definition.profileNo(),
                        external.accountRef(),
                        external.storeRef(),
                        definition.identityNamespace(),
                        Set.of("CATALOG", "ORDER", "FULFILLMENT", "AVAILABILITY"),
                        MockCommercePlatform.Scenario.SUCCESS));
        return external;
    }

    ChannelConnectionVO authorizeAndConnect(String entity, String store, ExternalStore external) {
        var accounts = context.getBean(ChannelAccountService.class);
        var authorizations = context.getBean(ChannelAuthorizationService.class);
        var account =
                accounts.createAccount(
                        entity,
                        "SYSTEM",
                        new CreateChannelAccountReq(
                                gateway.registration().definition().profileNo(),
                                external.accountRef()),
                        key());
        var operation =
                authorizations.authorize(
                        entity,
                        account.accountNo(),
                        "SYSTEM",
                        new AuthorizeChannelAccountReq(account.version(), "MOCK_MERCHANT"),
                        key());
        var start =
                context.getBean(ChannelOperationService.class)
                        .getOperation(entity, operation.operationNo());
        assertThat(start.authorizationUrl()).isNotBlank();
        String state =
                Arrays.stream(URI.create(start.authorizationUrl()).getRawQuery().split("&"))
                        .filter(part -> part.startsWith("state="))
                        .map(part -> URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8))
                        .findFirst()
                        .orElseThrow();
        authorizations.completeAuthorization("MOCK", state, "mock-approved", null);
        var connections = context.getBean(ChannelConnectionService.class);
        var draft =
                connections.createConnection(
                        entity,
                        store,
                        "SYSTEM",
                        new CreateChannelConnectionReq(
                                account.accountNo(), external.storeRef(), Owner.FOOD, "INSTANT"),
                        key());
        return connections.setStatus(
                entity,
                store,
                draft.connectionNo(),
                "SYSTEM",
                new SetStatus(draft.version(), "ACTIVE", "Original native Mock fixture"),
                key());
    }

    Published publish(
            String entity,
            String store,
            ChannelConnectionVO connection,
            String menu,
            String revision)
            throws Exception {
        var scopes = context.getBean(CatalogScopeService.class);
        var scope =
                scopes.createScope(
                        entity,
                        store,
                        "SYSTEM",
                        new CreateCatalogScopeReq(
                                connection.connectionNo(),
                                "STORE",
                                "Native vertical menu",
                                List.of(new SourceReq(Owner.FOOD, menu, revision, 0))),
                        key());
        var catalog =
                context.getBean(CatalogPublicationService.class)
                        .preview(
                                entity,
                                store,
                                scope.scopeNo(),
                                "SYSTEM",
                                new PreviewCatalogReq(scope.version()),
                                key());
        var run =
                context.getBean(SyncRunService.class)
                        .submit(
                                entity,
                                store,
                                "SYSTEM",
                                new SubmitSyncRunReq(
                                        scope.scopeNo(),
                                        catalog.snapshotNo(),
                                        catalog.hash(),
                                        catalog.scopeVersion(),
                                        "Native vertical original publication"),
                                key());
        var recovery = context.getBean(CommerceRecoveryService.class);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (!catalog.snapshotNo()
                .equals(
                        scopes.getScope(entity, store, scope.scopeNo())
                                .lastConfirmedSnapshotNo())) {
            if (Instant.now().isAfter(deadline))
                throw new AssertionError("Original Mock catalog publication did not finish");
            recovery.recoverDue(100);
            Thread.sleep(100);
        }
        // Genuine policy follows actual published currency and original verified store identity.
        var proof =
                context.getBean(ChannelCatalogAuthorityService.class)
                        .capture(
                                new Query(
                                        entity,
                                        store,
                                        connection.connectionNo(),
                                        connection.revisionNo()));
        var policy =
                context.getBean(IndustryChannelOrderPolicyService.class)
                        .save(
                                new Scope("MAIN", entity, store),
                                "SYSTEM",
                                proof.connection().channelStoreKey(),
                                new SaveReq(
                                        0L,
                                        catalog.currency(),
                                        true,
                                        false,
                                        true,
                                        1_000_000L,
                                        10_000_000L,
                                        900),
                                key());
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.currency()).isEqualTo(catalog.currency());
        return new Published(connection, catalog, run.runNo());
    }

    record Provisioned(
            FoodChannelVerticalFixture.References references,
            FoodChannelVerticalMenuFixture.Menu menu) {}

    FoodChannelVerticalFixture.References provision(int hostPort) throws Exception {
        return provision(hostPort, "RECIPE_MATERIAL").references();
    }

    Provisioned provision(int hostPort, String branchKind) throws Exception {
        String entity = "M0001", store = FoodNonPaymentNativeHttpIT.STORE;
        var sql = context.getBean(JdbcTemplate.class);
        var external = externalStore(entity, store);
        var connection = authorizeAndConnect(entity, store, external);
        var food = new FoodChannelVerticalMenuFixture(context, sql, json, hostPort);
        var menu = food.publish(external, branchKind);
        var published = publish(entity, store, connection, menu.number(), menu.revision());
        var recovery = context.getBean(CommerceRecoveryService.class);
        Instant deadline = Instant.now().plusSeconds(60);
        while (mapping(entity, store, connection.connectionNo(), ObjectType.SKU, menu.skuNo())
                == null) {
            if (Instant.now().isAfter(deadline))
                throw new AssertionError("Actual managed SKU projection not ready");
            recovery.recoverDue(100);
            context.getBean(ai.neargo.shop.commerce.service.ResourceMappingVerificationService.class)
                    .verifyDue(100);
            Thread.sleep(100);
        }
        // A real future order with no original service slot fails before source persistence. This
        // creates
        // the genuine command-before-source crash boundary without replacing any owner or inserting
        // a row.
        var scheduled =
                supplierDocument(
                        entity,
                        store,
                        external,
                        published,
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        Instant.now().plusSeconds(1800));
        String cancelledNo = callback(entity, store, connection, scheduled, hostPort);
        context.getBean(ChannelOrderImportService.class).recoverDue(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT import_snapshot FROM cim_order_link WHERE"
                                        + " channel_order_no=?",
                                String.class,
                                cancelledNo))
                .isNotBlank();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_channel_order_source WHERE"
                                        + " external_order_ref=?",
                                Integer.class,
                                scheduled.externalOrderRef()))
                .isZero();
        var cancelled =
                new ChannelOrderGateway.Document(
                        scheduled.schemaVersion(),
                        scheduled.externalAccountRef(),
                        scheduled.externalStoreRef(),
                        scheduled.externalOrderRef(),
                        "2",
                        2L,
                        scheduled.catalogHash(),
                        scheduled.currency(),
                        ChannelOrderGateway.State.CANCELLED,
                        scheduled.paymentState(),
                        scheduled.originalPaymentRef(),
                        scheduled.fulfillment(),
                        scheduled.orderedAt(),
                        scheduled.requestedAt(),
                        scheduled.customer(),
                        scheduled.lines(),
                        scheduled.totals());
        assertThat(callback(entity, store, connection, cancelled, hostPort)).isEqualTo(cancelledNo);
        var happy =
                supplierDocument(
                        entity,
                        store,
                        external,
                        published,
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        null);
        var unknown =
                supplierDocument(
                        entity,
                        store,
                        external,
                        published,
                        ChannelOrderGateway.State.WAITING_ACCEPTANCE,
                        null);
        return new Provisioned(
                new FoodChannelVerticalFixture.References(
                        entity,
                        store,
                        callback(entity, store, connection, happy, hostPort),
                        callback(entity, store, connection, unknown, hostPort),
                        cancelledNo),
                menu);
    }

    private ChannelOrderGateway.Document supplierDocument(
            String entity,
            String store,
            ExternalStore external,
            Published published,
            ChannelOrderGateway.State state,
            Instant requestedAt)
            throws Exception {
        var document = published.catalog().document();
        var offer = document.details().offers().getFirst();
        var sku = offer.variants().getFirst();
        var originalTax =
                document.taxBases().stream()
                        .filter(t -> t.taxBasisRef().equals(sku.price().taxBasisRef()))
                        .findFirst()
                        .orElseThrow();
        // The original native pricing fixture explicitly establishes zero EXCLUSIVE merchant tax.
        // Refuse a different owner definition instead of inventing supplier tax arithmetic.
        assertThat(originalTax.tax().rateBp()).isZero();
        assertThat(originalTax.tax().priceTaxMode()).isEqualTo("EXCLUSIVE");
        assertThat(originalTax.entityNo()).isEqualTo(entity);
        assertThat(originalTax.storeNo()).isEqualTo(store);
        var offerIdentity =
                mapping(
                        entity,
                        store,
                        published.connection().connectionNo(),
                        ObjectType.OFFER,
                        offer.originalOfferRef());
        var skuIdentity =
                mapping(
                        entity,
                        store,
                        published.connection().connectionNo(),
                        ObjectType.SKU,
                        sku.skuRef());
        assertThat(offerIdentity).isNotNull();
        assertThat(skuIdentity).isNotNull();
        long amount = sku.price().amountMinor();
        var selections = new java.util.ArrayList<ChannelOrderGateway.Selection>();
        for (var group : sku.optionGroups())
            for (var choice : group.choices()) {
                var quantity = new java.math.BigDecimal(choice.quantity().defaultQuantity());
                if (quantity.signum() == 0) continue;
                assertThat(choice.price().taxBasisRef()).isEqualTo(sku.price().taxBasisRef());
                long extra =
                        Math.multiplyExact(choice.price().amountMinor(), quantity.longValueExact());
                var identity =
                        awaitMapping(
                                entity,
                                store,
                                published.connection().connectionNo(),
                                ObjectType.OPTION,
                                choice.choiceRef());
                selections.add(
                        new ChannelOrderGateway.Selection(
                                "option-" + selections.size(),
                                "OPTION",
                                identity,
                                quantity.toPlainString(),
                                extra));
                amount = Math.addExact(amount, extra);
            }
        for (var group : sku.bundleGroups())
            for (var choice : group.choices()) {
                var quantity = new java.math.BigDecimal(choice.quantity().defaultQuantity());
                if (quantity.signum() == 0) continue;
                assertThat(choice.extraPrice().taxBasisRef()).isEqualTo(sku.price().taxBasisRef());
                long extra =
                        Math.multiplyExact(
                                choice.extraPrice().amountMinor(), quantity.longValueExact());
                var identity =
                        awaitMapping(
                                entity,
                                store,
                                published.connection().connectionNo(),
                                ObjectType.BUNDLE_MEMBER,
                                choice.choiceRef());
                selections.add(
                        new ChannelOrderGateway.Selection(
                                "bundle-" + selections.size(),
                                "BUNDLE_MEMBER",
                                identity,
                                quantity.toPlainString(),
                                extra));
                amount = Math.addExact(amount, extra);
            }
        String ref = "vertical-order-" + key();
        var line =
                new ChannelOrderGateway.Line(
                        "main",
                        offerIdentity,
                        skuIdentity,
                        "1",
                        offer.measurement().unitCode(),
                        amount,
                        0,
                        0,
                        amount,
                        selections,
                        "Native Mock original invoice");
        return new ChannelOrderGateway.Document(
                1,
                external.accountRef(),
                external.storeRef(),
                ref,
                "1",
                1L,
                published.catalog().hash(),
                document.currency(),
                state,
                ChannelOrderGateway.PaymentState.CASH_ON_DELIVERY,
                null,
                ChannelOrderGateway.Fulfillment.INSTANT_DELIVERY,
                Instant.now(),
                requestedAt,
                new ChannelOrderGateway.Customer(
                        "customer-" + key(),
                        "Native Mock customer",
                        "13800000000",
                        "Native Mock delivery address"),
                List.of(line),
                new ChannelOrderGateway.Totals(0, 0, 0, 0, amount, amount));
    }

    private Identity awaitMapping(
            String e, String s, String connection, ObjectType type, String source)
            throws Exception {
        var deadline = Instant.now().plusSeconds(60);
        Identity result;
        while ((result = mapping(e, s, connection, type, source)) == null) {
            if (Instant.now().isAfter(deadline))
                throw new AssertionError("Original terminal child mapping not verified");
            context.getBean(CommerceRecoveryService.class).recoverDue(100);
            context.getBean(ai.neargo.shop.commerce.service.ResourceMappingVerificationService.class)
                    .verifyDue(100);
            Thread.sleep(100);
        }
        return result;
    }

    private Identity mapping(
            String entity, String store, String connection, ObjectType kind, String source) {
        return context
                .getBean(ResourceMappingService.class)
                .listMappings(entity, store, 1, 100, connection, kind, null, source)
                .records()
                .stream()
                .filter(
                        m ->
                                m.active() != null
                                        && "MANAGED".equals(m.active().mappingOrigin())
                                        && "VERIFIED".equals(m.active().validationStatus()))
                .map(m -> m.externalIdentity())
                .findFirst()
                .orElse(null);
    }

    private String callback(
            String entity,
            String store,
            ChannelConnectionVO connection,
            ChannelOrderGateway.Document document,
            int hostPort)
            throws Exception {
        post(
                "/mock/v1/admin/orders",
                new MockCommercePlatform.OrderSeed(
                        connection.profileNo(), document, MockCommercePlatform.Scenario.SUCCESS));
        var sql = context.getBean(JdbcTemplate.class);
        String credential =
                sql.queryForObject(
                        "SELECT a.credential_ref FROM cim_authorization_revision a JOIN cim_account"
                                + " c ON c.active_authorization_no=a.authorization_no AND"
                                + " c.entity_no=a.entity_no WHERE c.account_no=?",
                        String.class,
                        connection.accountNo());
        post(
                "/mock/v1/admin/events/emit",
                new MockCommercePlatform.Emission(
                        "http://127.0.0.1:"
                                + hostPort
                                + "/callback/commerce/MOCK/"
                                + connection.connectionNo(),
                        credential,
                        new MockCommercePlatform.MockEvent(
                                "event-" + key(),
                                document.state() == ChannelOrderGateway.State.CANCELLED
                                        ? "ORDER_CANCELLED"
                                        : "ORDER_CREATED",
                                document.externalStoreRef(),
                                document.externalOrderRef(),
                                document.revisionRef(),
                                Instant.now()),
                        1,
                        false));
        context.getBean(ChannelInboundServiceImpl.class).recoverDue(100);
        return sql.queryForObject(
                "SELECT channel_order_no FROM cim_order_link WHERE entity_no=? AND store_no=?"
                        + " AND connection_no=? AND external_order_ref=?",
                String.class,
                entity,
                store,
                connection.connectionNo(),
                document.externalOrderRef());
    }

    private void post(String path, Object value) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + platform.port() + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)))
                        .build();
        assertThat(
                        HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(200);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
