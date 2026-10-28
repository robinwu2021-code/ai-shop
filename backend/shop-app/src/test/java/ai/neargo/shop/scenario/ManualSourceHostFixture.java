package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.invbridge.port.OriginalStockDispositionPort;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationDispositionService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.port.MemberCustomerIdentityPortImpl;
import ai.neargo.shop.member.port.MemberGuestRegistrationPortImpl;
import ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl;
import ai.neargo.shop.product.port.ServiceCatalogFactsPortImpl;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.product.service.impl.StockDispositionServiceImpl;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockDispositionContract.ReservationOwner;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.trade.order.service.create.CounterOrderPlacementService;
import ai.neargo.shop.trade.order.service.create.impl.CounterOrderPlacementServiceImpl;
import ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.CheckoutServiceImpl;
import ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Real embedded owners share the primary factory; fixture setup never issues consumer proof
 * tickets.
 */
@Import(BeautySourceCheckoutIT.TestClockSelection.class)
@SpringBootTest(
        classes = {ShopApplication.class, CheckoutFlowFixture.ClockConfig.class},
        properties = {
            "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL:jdbc:h2:mem:manual-source;MODE=MySQL}",
            "shop.member-code.enabled=true",
            "shop.catalog.publication.archive-only=true",
            "shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "shop.beauty.enabled=true",
            "shop.beauty.deployment=embedded",
            "shop.beauty.migration.mode=migrate",
            "shop.beauty.publication.archive-only=true",
            "shop.inventory.enabled=true",
            "shop.inventory.flyway-enabled=false",
            "shop.inventory.datasource.url=${MANUAL_INVENTORY_MYSQL_URL}",
            "shop.inventory.datasource.username=root",
            "shop.inventory.datasource.password=${MANUAL_SERVICE_TEST_PASSWORD}",
            "shop.inventory.stock-authority=INVENTORY"
        })
abstract class ManualSourceHostFixture extends CheckoutFlowFixture {
    @Autowired protected ObjectMapper json;
    @Autowired protected WebApplicationContext web;
    @MockitoSpyBean protected CustomerIdentityPort customers;
    @MockitoSpyBean protected GuestRegistrationPort guests;
    @Autowired protected MerchantOperatorAccessPort operators;
    @MockitoSpyBean protected ServiceCatalogFactsPort serviceCatalog;
    @MockitoSpyBean protected ManualServiceAdmissionPort manual;
    @Autowired protected ManualServiceAdmissionService manualOwner;
    @Autowired protected CheckoutService checkoutOwner;
    @Autowired protected OrderService orderOwner;
    @Autowired protected CounterOrderPlacementService counterOwner;
    @Autowired protected StockDispositionPort originalStock;
    @Autowired protected StockPortImpl platformStock;
    @Autowired protected StockDispositionService platformDispositions;
    @Autowired protected InventoryAclService inventoryAcl;
    @Autowired protected LocationService inventoryLocations;
    @Autowired protected StockCountService inventoryCounts;

    @Autowired
    @Qualifier("invDataSource")
    protected DataSource inventoryDataSource;

    @Autowired protected StockPort inventoryCommands;
    @Autowired protected ReservationDispositionService inventoryDisposition;
    protected String member;
    private MockMvc mvc;

    @BeforeEach
    void matrixOwnerOrigins() throws Exception {
        actualOrigins();
        origin(
                inventoryCommands,
                Class.forName(switch (((ReservationOwner) inventoryCommands).stockAuthority()) {
                    case "PLATFORM" -> "ai.neargo.shop.product.port.StockPortImpl";
                    case "DUAL" -> "ai.neargo.shop.invbridge.port.DualWriteStockPort";
                    default -> "ai.neargo.shop.invbridge.port.InventoryStockPort";
                }));
        origin(
                inventoryAcl,
                Class.forName("ai.neargo.shop.inventory.service.impl.InventoryAclServiceImpl"));
        origin(
                inventoryCounts,
                Class.forName("ai.neargo.shop.inventory.service.impl.StockCountServiceImpl"));
        origin(
                inventoryDisposition,
                Class.forName(
                        "ai.neargo.shop.inventory.service.impl.ReservationDispositionServiceImpl"));
        try (var connection = inventoryDataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    /**
     * Override the consumer checkout fixture's proof issuance while retaining its actual HOST
     * configuration.
     */
    @Override
    @BeforeEach
    void fixture() {
        testClock.at = Instant.now();
        String id = UUID.randomUUID().toString().substring(0, 8);
        entity = "ME-" + id;
        store = "MS-" + id;
        actor = "MA-" + id;
        buyer = "MU-" + id;
        goods = "MG-" + id;
        sku = "MK-" + id;
        member = "MM-" + id;
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,status,created_at,updated_at)"
                        + " VALUES (?,'manual source owner','ACTIVE',NOW(),NOW())",
                entity);
        jdbc.update(
                "INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at)"
                        + " VALUES (?,?,'manual source store','ACTIVE',NOW(),NOW())",
                entity,
                store);
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                actor,
                actor + "-user");
        jdbc.update(
                "INSERT INTO"
                        + " mch_qualification(qual_no,entity_no,qual_type,qual_name,"
                        + "qual_number,status,created_at,updated_at)"
                        + " VALUES (?,?,'BUSINESS_LICENSE','manual source license','FIXTURE',"
                        + "'VALID',NOW(),NOW())",
                "license-" + id,
                entity);
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'manual source buyer','NORMAL',NOW(),NOW())",
                buyer);
        jdbc.update(
                "INSERT INTO usr_person(person_no,phone_hash,user_no,status,created_at,updated_at)"
                        + " VALUES (?,?,?,'ACTIVE',NOW(),NOW())",
                "person-" + id,
                "hash-" + id,
                buyer);
        jdbc.update(
                "INSERT INTO mbr_member(member_no,entity_no,person_no,status,source,joined_at,"
                        + "created_at,updated_at) VALUES (?,?,?,'ACTIVE','MANUAL',?,NOW(),NOW())",
                member,
                entity,
                "person-" + id,
                System.currentTimeMillis());
        jdbc.update(
                "INSERT INTO"
                        + " prd_goods(goods_no,entity_no,store_no,title,type,on_sale,"
                        + "audit_status,fulfillments,duration_min,created_at,updated_at)"
                        + " VALUES (?,?,?,'Manual source service',"
                        + "'SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',30,NOW(),NOW())",
                goods,
                entity,
                store);
        jdbc.update(
                "INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,"
                        + "created_at,updated_at) VALUES (?,?,?,'CN',101,100,0,NOW(),NOW())",
                sku,
                goods,
                entity);
        String inventoryOwner = inventoryAcl.ownerIdOf(entity);
        String inventoryItem = inventoryAcl.itemIdOf(entity, sku);
        inventoryCounts.adjustOne(
                inventoryOwner,
                inventoryLocations.defaultLocation(inventoryOwner),
                inventoryItem,
                100,
                "OTHER",
                "SYSTEM");
        scope = new CheckoutPort.CheckoutScope("MAIN", entity, store, actor);
        asMerchant(
                () -> {
                    assertThat(SecurityUtils.requireUser().realm().name()).isEqualTo("MERCHANT");
                    assertThat(SecurityUtils.currentToken()).isPresent();
                    catalogs.publish(
                            entity,
                            store,
                            actor,
                            new StoreSalesCatalogPort.PublishReq(
                                    0L,
                                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                                    "UTC",
                                    List.of(),
                                    List.of(
                                            new StoreSalesCatalogPort.Entry(
                                                    sku,
                                                    null,
                                                    0,
                                                    true,
                                                    StoreSalesCatalogPort.SalesMode.UNRESTRICTED,
                                                    List.of()))),
                            "catalog-" + id);
                    call(
                            bean("StorePolicyService"),
                            "publishPolicyVersion",
                            entity,
                            store,
                            actor,
                            dto(
                                    "PublishStorePolicyVersionReq",
                                    0L,
                                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                                    dto(
                                            "PublishStorePolicyVersionReq$Policy",
                                            30,
                                            dto(
                                                    "PublishStorePolicyVersionReq$CancellationRule",
                                                    60,
                                                    true),
                                            Set.of("PREPAID_SINGLE"))),
                            "policy-" + id);
                    call(
                            bean("ServiceDefinitionService"),
                            "publishDefinitionVersion",
                            entity,
                            store,
                            actor,
                            dto(
                                    "PublishServiceDefinitionVersionReq",
                                    sku,
                                    0L,
                                    dto("PublishServiceDefinitionVersionReq$Duration", 30, 0, 0),
                                    List.of(
                                            dto(
                                                    "PublishServiceDefinitionVersionReq$Requirement",
                                                    "MAIN",
                                                    "STAFF",
                                                    goods,
                                                    1,
                                                    0,
                                                    0,
                                                    30,
                                                    "ANY_QUALIFIED"))),
                            "definition-" + id);
                    return null;
                });
    }

    @Override
    protected <T> T asMerchant(Supplier<T> body) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, body);
    }

    protected void actualOrigins() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        origin(customers, MemberCustomerIdentityPortImpl.class);
        origin(guests, MemberGuestRegistrationPortImpl.class);
        origin(operators, MerchantOperatorAccessPortImpl.class);
        origin(serviceCatalog, ServiceCatalogFactsPortImpl.class);
        origin(bean("ServiceDefinitionService"), type("service.impl.ServiceDefinitionServiceImpl"));
        origin(bean("WorkOrderService"), type("service.impl.WorkOrderServiceImpl"));
    }

    /**
     * Genuine shared counter creation and original INVENTORY receipt precede any desired beauty
     * manual branch.
     */
    protected ManualOriginal admittedCounter(
            String workOrder,
            ManualServiceAdmissionPort.CounterCustomerRef selector,
            String suffix) {
        return admittedCounter(workOrder, selector, suffix, 1);
    }
    protected ManualOriginal admittedCounter(
            String workOrder,
            ManualServiceAdmissionPort.CounterCustomerRef selector,
            String suffix, int quantity) {
        origin(manual, ManualServiceAdmissionPortImpl.class);
        origin(manualOwner, ManualServiceAdmissionServiceImpl.class);
        origin(checkoutOwner, CheckoutServiceImpl.class);
        origin(orderOwner, OrderServiceImpl.class);
        origin(counterOwner, CounterOrderPlacementServiceImpl.class);
        origin(originalStock, OriginalStockDispositionPort.class);
        origin(platformStock, StockPortImpl.class);
        origin(platformDispositions, StockDispositionServiceImpl.class);
        var frozen =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT frozen_scope FROM bty_work_order_context"
                                        + " WHERE entity_no=? AND store_no=? AND work_order_no=?",
                                String.class,
                                entity,
                                store,
                                workOrder));
        String scopeHash = frozen.path("scopeHash").asText();
        assertThat(scopeHash).matches("[a-f0-9]{64}");
        var operation =
                new CheckoutPort.OperationRef("BEAUTY", "manual-bind-" + suffix + '-' + sku);
        var quote =
                asMerchant(
                        () ->
                                manual.prepareCounter(
                                        scope,
                                        new ManualServiceAdmissionPort.PrepareCounterReq(
                                                operation,
                                                "prepare-" + operation.operationNo(),
                                                selector,
                                                1,
                                                scopeHash,
                                                List.of(
                                                        new CheckoutPort.CheckoutLineReq(
                                                                "service-line", sku, quantity)),
                                                null,
                                                Map.of(),
                                                null)));
        assertThat(quote.purchaseAuthority()).isEqualTo("MERCHANT_RECORDED_IN_PERSON");
        assertThat(quote.quote().dueMinor()).isEqualTo(101L * quantity);
        var accepted =
                asMerchant(
                        () ->
                                manual.acceptCounter(
                                        scope,
                                        new ManualServiceAdmissionPort.AcceptCounterReq(
                                                operation,
                                                "accept-" + operation.operationNo(),
                                                quote.quote().quoteNo(),
                                                quote.quote().quoteRevision(),
                                                quote.quote().quoteHash(),
                                                quote.party().identityRevision(),
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                "MERCHANT_RECORDED_IN_PERSON")));
        var created =
                asMerchant(
                        () ->
                                manual.placeCounter(
                                        scope,
                                        new ManualServiceAdmissionPort.PlaceCounterReq(
                                                operation,
                                                accepted.confirmationNo(),
                                                "place-" + operation.operationNo())));
        assertThat(created.phase()).isEqualTo("CREATED");
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        created.orderNo());
        var receipt =
                asMerchant(
                        () ->
                                manual.confirm(
                                        scope,
                                        new ManualServiceAdmissionPort.ConfirmManualReceipt(
                                                operation,
                                                "receipt-" + created.orderNo(),
                                                revision,
                                                ManualServiceAdmissionPort.ManualMethod.CASH,
                                                101L * quantity,
                                                "CNY",
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                null)));
        assertThat(receipt.admissionState()).isEqualTo("ADMITTED");
        assertThat(receipt.declaredMinor()).isEqualTo(101L * quantity);
        assertThat(receipt.receiptProvenance()).isEqualTo("MANUAL_DECLARATION");
        var source =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                operation, created.subOrderNo())));
        assertThat(source.admissionState()).isEqualTo("ADMITTED");
        assertThat(source.party()).isEqualTo(quote.party());
        assertThat(source.orderNo()).isEqualTo(created.orderNo());
        assertThat(source.subOrderNo()).isEqualTo(created.subOrderNo());
        assertThat(source.admissionKind()).isEqualTo("MANUAL_OFFLINE");
        assertThat(source.manualObservationRevision()).isPositive();
        assertThat(source.manualObservationHash()).matches("[a-f0-9]{64}");
        assertThat(source.lines()).hasSize(1);
        var line = source.lines().getFirst();
        assertThat(line.clientLineRef()).isEqualTo("service-line");
        assertThat(line.goodsNo()).isEqualTo(goods);
        assertThat(line.skuNo()).isEqualTo(sku);
        assertThat(line.orderedQuantity()).isEqualTo(quantity);
        assertThat(line.availableQuantity()).isEqualTo(quantity);
        assertThat(line.uom()).isEqualTo("COUNT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_line m JOIN ord_item i ON"
                                        + " i.id=m.order_item_id AND i.sub_order_no=m.sub_order_no"
                                        + " WHERE m.operation_no=? AND m.sub_order_no=? AND"
                                        + " m.source_line_no=? AND m.goods_no=? AND m.sku_no=? AND"
                                        + " m.quantity=? AND i.qty=?",
                                Integer.class,
                                operation.operationNo(),
                                created.subOrderNo(),
                                line.sourceLineNo(),
                                goods,
                                sku, quantity, quantity))
                .isOne();
        var declaration =
                jdbc.queryForMap(
                        "SELECT stock_authority,stock_manifest_hash,stock_state"
                                + " FROM ord_manual_service_admission WHERE order_no=?",
                        created.orderNo());
        assertThat(declaration).containsEntry("stock_authority", ((
            ReservationOwner) inventoryCommands).stockAuthority());
        assertThat(declaration.get("stock_state")).isIn("ALLOCATED", "CONSUMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Integer.class,
                                operation.operationNo()))
                .isZero();
        System.out.println(
                "TASK67_MANUAL_SOURCE "
                        + source.party().kind()
                        + ' '
                        + source.orderNo()
                        + ' '
                        + source.subOrderNo()
                        + ' '
                        + line.sourceLineNo()
                        + ' '
                        + source.manualObservationHash());
        var inventory = new JdbcTemplate(inventoryDataSource);
        try (var connection = inventoryDataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        if (!"INVENTORY".equals(((ReservationOwner) inventoryCommands).stockAuthority())) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_stock_lock WHERE tenant_no='MAIN' AND lock_no=?"
                    + " AND source_manifest_hash=? AND sku_no=? AND source_store_no=? AND qty=?"
                    + " AND status IN ('ALLOCATED','CONFIRMED') AND confirmation_operation_no IS NOT NULL",
                    Integer.class, created.orderNo(), declaration.get("stock_manifest_hash"), sku, store,
                        quantity)).isOne();
        } else {
        assertThat(
                        inventory.queryForObject(
                                "SELECT COUNT(*) FROM inv_reservation r JOIN inv_reservation_line l"
                                    + " ON l.owner_id=r.owner_id AND"
                                    + " l.reservation_id=r.reservation_id WHERE r.external_ref=?"
                                    + " AND r.source_manifest_hash=? AND r.status IN"
                                    + " ('COMMITTED','ALLOCATED') AND l.source_sku_no=? AND"
                                    + " l.source_store_no=? AND l.qty=? AND l.issued_qty IN (0,?)"
                                    + " AND l.released_qty=0",
                                Integer.class,
                                created.orderNo(),
                                declaration.get("stock_manifest_hash"),
                                sku,
                                store, quantity, quantity))
                .isOne();
        System.out.println(
                "TASK67_INVENTORY_ORIGINAL "
                        + created.orderNo()
                        + " "
                        + inventoryDataSource.getClass().getName());

        }

        return new ManualOriginal(workOrder, source);
    }

    protected JsonNode http(String method, String path, Object body, String key, boolean consumer) {
        if (mvc == null)
            mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        var realm = consumer ? Realm.CONSUMER : Realm.MERCHANT;
        var pool = tokens.of(realm);
        String token =
                pool.issue(
                        new SessionData(
                                consumer
                                        ? LoginUser.consumer(buyer, "source reader")
                                        : LoginUser.merchant(actor, "source cashier")));
        try {
            var request =
                    MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON);
            if (!consumer) request.header("X-Store-No", store);
            if (key != null) request.header("Idempotency-Key", key);
            if (body != null) request.content(json.writeValueAsString(body));
            var response = mvc.perform(request).andReturn().getResponse();
            assertThat(response.getStatus()).isBetween(200, 499);
            return json.readTree(response.getContentAsString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            pool.revoke(token);
        }
    }

    protected JsonNode successful(JsonNode response) {
        assertThat(response.path("code").asInt(-1)).as(response.toString()).isZero();
        return response.path("data");
    }

    protected String open(Map<String, Object> selector, String suffix) {
        var body = new HashMap<String, Object>(selector);
        body.put("storeNo", store);
        body.put("items", List.of(Map.of("skuNo", sku, "qty", 1)));
        return successful(
                        http(
                                "POST",
                                "/biz/x/beauty/work-orders",
                                body,
                                "open-" + suffix + '-' + sku,
                                false))
                .path("workOrderNo")
                .asText();
    }

    protected Map<String, Object> existingRequest(
            String workOrder, ManualServiceAdmissionPort.ManualSourceFacts source) {
        return existingRequest(
                workOrder,
                "MANUAL_EXISTING",
                source.operation().operationNo(),
                source.subOrderNo(),
                source.manualObservationRevision(),
                source.manualObservationHash(),
                source.lines().getFirst().sourceLineNo());
    }

    protected Map<String, Object> existingRequest(
            String workOrder, CheckoutPort.CheckoutSourceFacts source) {
        return existingRequest(
                workOrder,
                "EXISTING",
                source.operation().operationNo(),
                source.subOrderNo(),
                source.sourceRevision(),
                source.sourceHash(),
                source.lines().getFirst().sourceLineNo());
    }

    private Map<String, Object> existingRequest(
            String workOrder,
            String mode,
            String operation,
            String sourceNo,
            long revision,
            String hash,
            String line) {
        var root =
                jdbc.queryForMap(
                        "SELECT version,scope_revision FROM bty_work_order WHERE tenant_no='MAIN'"
                            + " AND entity_no=? AND store_no=? AND work_order_no=? AND deleted=0",
                        entity,
                        store,
                        workOrder);
        var task =
                jdbc.queryForMap(
                        "SELECT t.task_no,t.version,t.binding_revision FROM bty_service_task t JOIN"
                            + " bty_work_order w ON w.tenant_no=t.tenant_no AND"
                            + " w.entity_no=t.entity_no AND w.work_order_no=t.work_order_no WHERE"
                            + " t.tenant_no='MAIN' AND t.entity_no=? AND w.store_no=? AND"
                            + " t.work_order_no=? AND t.deleted=0 AND w.deleted=0 AND"
                            + " t.binding_revision=0 ORDER BY t.task_no LIMIT 1",
                        entity,
                        store,
                        workOrder);
        return Map.of(
                "expectedRevision",
                root.get("version"),
                "expectedScopeRevision",
                root.get("scope_revision"),
                "tasks",
                List.of(
                        Map.of(
                                "taskNo",
                                task.get("task_no"),
                                "expectedRevision",
                                task.get("version"),
                                "expectedBindingRevision",
                                task.get("binding_revision"))),
                "additions",
                List.of(),
                "source",
                Map.of(
                        "mode",
                        mode,
                        "activityChoices",
                        Map.of(),
                        "checkoutOperationNo",
                        operation,
                        "subOrderNo",
                        sourceNo,
                        "sourceRevision",
                        revision,
                        "sourceHash",
                        hash,
                        "lines",
                        List.of(
                                Map.of(
                                        "selectionRef",
                                        task.get("task_no"),
                                        "sourceLineNo",
                                        line))));
    }

    protected JsonNode bind(ManualOriginal original, String suffix) {
        return successful(
                http(
                        "POST",
                        "/biz/x/beauty/work-orders/"
                                + original.workOrder()
                                + "/service-submissions",
                        existingRequest(original.workOrder(), original.source()),
                        "bind-" + suffix + '-' + sku,
                        false));
    }

    protected GuestRegistrationPort.Registration guest(String suffix) {
        return asMerchant(
                () ->
                        guests.register(
                                new GuestRegistrationPort.Scope(
                                        entity,
                                        store,
                                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor)),
                                new GuestRegistrationPort.RegisterCommand(
                                        null, null, true, "guest-" + suffix + '-' + sku)));
    }

    protected String realConsumerReference() {
        String code = asBuyer(() -> identities.issue("MAIN", buyer).memberCode());
        return asMerchant(
                        () ->
                                identities.resolve(
                                        new MemberCodeScope("MAIN", entity, store, actor), code))
                .customerRef();
    }

    protected void claim(GuestRegistrationPort.Registration guest) {
        String proof = realConsumerReference();
        asMerchant(
                () ->
                        call(
                                bean("GuestRegistrationService"),
                                "claimGuest",
                                entity,
                                store,
                                actor,
                                guest.guestNo(),
                                dto("ClaimGuestReq", proof, guest.claimToken()),
                                "claim-" + guest.guestNo()));
    }

    protected Object observationOwner() {
        return web.getBean(type("service.impl.ServiceSourceObservation"));
    }

    protected Object manualCanonical(ManualServiceAdmissionPort.ManualSourceFacts source) {
        try {
            return type("service.impl.ServiceSourceObservation$ManualCanonical")
                    .getConstructors()[0]
                    .newInstance(
                            entity,
                            store,
                            source.party(),
                            source.operation().operationNo(),
                            source.orderNo(),
                            source.subOrderNo());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    protected Map<String, Object> observationRow(String source) {
        return jdbc.queryForMap(
                "SELECT * FROM bty_source_observation WHERE tenant_no='MAIN' AND entity_no=?"
                        + " AND store_no=? AND source_no=? AND deleted=0",
                entity,
                store,
                source);
    }

    protected ManualOriginal admittedM1(String workOrder, String suffix) {
        String proof = realConsumerReference();
        var operation = new CheckoutPort.OperationRef("BEAUTY", "m1-" + suffix + '-' + sku);
        String scopeHash =
                json.readTree(
                                jdbc.queryForObject(
                                        "SELECT frozen_scope FROM bty_work_order_context WHERE"
                                                + " entity_no=? AND store_no=? AND work_order_no=?",
                                        String.class,
                                        entity,
                                        store,
                                        workOrder))
                        .path("scopeHash")
                        .asText();
        var quote =
                asMerchant(
                        () ->
                                manual.prepare(
                                        scope,
                                        new CheckoutPort.PrepareCheckoutReq(
                                                operation,
                                                "prepare-" + operation.operationNo(),
                                                proof,
                                                1,
                                                scopeHash,
                                                List.of(
                                                        new CheckoutPort.CheckoutLineReq(
                                                                "service-line", sku, 1)),
                                                "OFFLINE",
                                                null,
                                                Map.of(),
                                                null)));
        var consent =
                asBuyer(
                        () ->
                                checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                operation,
                                                quote.quoteNo(),
                                                quote.quoteRevision(),
                                                quote.quoteHash(),
                                                "consent-" + operation.operationNo())));
        var created =
                asBuyer(
                        () ->
                                checkout.place(
                                        new CheckoutPort.PlaceCheckoutReq(
                                                operation,
                                                consent.confirmationNo(),
                                                "place-" + operation.operationNo())));
        assertThat(created.phase()).isEqualTo("CREATED");
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        created.orderNo());
        assertThat(
                        asMerchant(
                                        () ->
                                                manual.confirm(
                                                        scope,
                                                        new ManualServiceAdmissionPort
                                                                .ConfirmManualReceipt(
                                                                operation,
                                                                "cash-" + operation.operationNo(),
                                                                revision,
                                                                ManualServiceAdmissionPort
                                                                        .ManualMethod.CASH,
                                                                101,
                                                                "CNY",
                                                                testClock
                                                                        .instant()
                                                                        .truncatedTo(
                                                                                ChronoUnit.MILLIS),
                                                                null)))
                                .admissionState())
                .isEqualTo("ADMITTED");
        var facts =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                operation, created.subOrderNo())));
        assertThat(facts.purchaseAuthority()).isEqualTo("CONSUMER_SESSION");
        assertThat(facts.party().identityRevision()).isEqualTo(1L);
        return new ManualOriginal(workOrder, facts);
    }

    protected record ManualOriginal(
            String workOrder, ManualServiceAdmissionPort.ManualSourceFacts source) {}

    protected Object bean(String name) {
        return web.getBean(type("service." + name));
    }

    protected static Class<?> type(String name) {
        try {
            return Class.forName("ai.neargo.shop.beauty." + name);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(
                    "Actual embedded beauty dependency is required", failure);
        }
    }

    protected static Object dto(String name, Object... arguments) {
        for (var constructor : type("dto." + name).getConstructors()) {
            if (constructor.getParameterCount() != arguments.length) {
                continue;
            }
            try {
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Actual beauty DTO construction failed", failure);
            }
        }
        throw new IllegalArgumentException("Missing retained beauty constructor: " + name);
    }

    protected static Object call(Object receiver, String name, Object... arguments) {
        for (var method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            try {
                return method.invoke(receiver, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Actual beauty call failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Actual beauty reflection failed", failure);
            }
        }
        throw new IllegalArgumentException("Missing actual beauty method: " + name);
    }

    private static void origin(Object bean, Class<?> expected) {
        var actual = AopUtils.getTargetClass(bean);
        assertThat(actual).isEqualTo(expected);
        String location = actual.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(location).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
        System.out.println("TASK67_CODE_SOURCE " + actual.getName() + ' ' + location);
    }
}
