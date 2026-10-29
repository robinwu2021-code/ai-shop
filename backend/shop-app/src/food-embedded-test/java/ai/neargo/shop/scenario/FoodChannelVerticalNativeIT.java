package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform;
import ai.neargo.shop.commerce.channel.mock.MockCommercePlatform.Scenario;
import ai.neargo.shop.commerce.dto.ChannelOrderContracts.Accept;
import ai.neargo.shop.commerce.dto.ChannelOrderContracts.Recover;
import ai.neargo.shop.commerce.dto.OperationContracts.Schedule;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.commerce.service.ChannelOperationService;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.commerce.service.impl.ChannelFulfillmentServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOrderDecisionService;
import ai.neargo.shop.commerce.service.impl.ChannelOrderImportService;
import ai.neargo.shop.event.outbox.DeliveryRef;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.RetryPolicy;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.FulfillmentPublication;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.PublicationStatus;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

/**
 * Self-provisioning native CHANNEL integration on an explicitly disposable MySQL platform database
 * and independent Inventory database. Select food-embedded, food-commerce-embedded and
 * commerce-embedded together, FOOD_HOST_SMOKE_DISPOSABLE=true and FOOD_HOST_SMOKE_JDBC_URL as the
 * original host fixture. Boot creates a temporary persistent loopback Mock (port 0), original
 * profile/auth/identity/catalog, real merchant product, published recipe/menu/station, actual stock
 * and funding policy through their owners. No Core, FOOD, facts, number coordinator or Inventory
 * bean is replaced. Optional FOOD_CHANNEL_VERTICAL_REFERENCES may name a references-only
 * preprovisioned fixture; then set matching FOOD_CHANNEL_VERTICAL_MOCK_DIRECTORY/TOKEN as well.
 * This suite never calls real vendors.
 */
@ActiveProfiles("commerce-mock")
@TestPropertySource(
        properties = {
            "shop.seed.enabled=true",
            "shop.commerce.enabled=true",
            "shop.commerce.migration=MIGRATE",
            "shop.food.commerce.source-validation.enabled=true",
            "shop.food.commerce.binding.enabled=true",
            "shop.food.commerce.price-policy.enabled=true",
            "shop.food.commerce.catalog.enabled=true",
            "shop.commerce.callbacks.enabled=true",
            "shop.commerce.mock.enabled=true",
            "shop.commerce.mock.bootstrap.enabled=true",
            "shop.commerce.mock.port=0",
            "shop.commerce.mock.profile-no=MOCK_CN_VERTICAL",
            "shop.commerce.mock.market=CN",
            "shop.commerce.mock.identity-namespace=MOCK:CN",
            "shop.food.channel.enabled=true",
            "shop.food.commerce.fulfillment-events.enabled=true",
            "shop.services.internal-token=food-native-only",
            "shop.trade.channel.enabled=true",
            "shop.food.menu-publication-enabled=true",
            "shop.food.recipe-publication-enabled=true",
            "shop.food.station-publication-enabled=true",
            "shop.inventory.stock-authority=INVENTORY",
            "shop.outbox.sources.platform.mode=UNIFIED",
            "shop.outbox.sources.inventory.mode=UNIFIED"
        })
class FoodChannelVerticalNativeIT extends FoodEmbeddedStartupIT {
    private static final String MOCK_TOKEN =
            System.getenv()
                    .getOrDefault(
                            "FOOD_CHANNEL_VERTICAL_MOCK_TOKEN",
                            "native-channel-" + UUID.randomUUID());
    private static final Path MOCK_DIRECTORY = mockDirectory();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MockCommercePlatform platform;
    @Autowired ChannelOrderService orders;
    @Autowired ChannelOrderImportService imports;
    @Autowired ChannelOrderDecisionService decisions;
    @Autowired ChannelOperationService operations;
    @Autowired DiningChannelOrderService restaurant;
    @Autowired IndustryChannelOrderPort core;
    @Autowired InventoryDemandPlanPort inventory;

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry properties) {
        properties.add("shop.commerce.mock.directory", () -> MOCK_DIRECTORY.toString());
        properties.add("shop.commerce.mock.token", () -> MOCK_TOKEN);
    }

    @Override
    @Test
    void nativeMigrationsAndRealHostStartTogether() throws ClassNotFoundException {
        assertThat(
                        sql.queryForObject(
                                "SELECT MAX(CAST(version AS UNSIGNED)) FROM fnb_flyway_history"
                                        + " WHERE success=1",
                                Integer.class))
                .isGreaterThanOrEqualTo(30);
        assertThat(AopUtils.getTargetClass(core).getName())
                .isEqualTo("ai.neargo.shop.trade.port.IndustryChannelOrderPortImpl");
        assertThat(AopUtils.getTargetClass(restaurant).getName())
                .isEqualTo("ai.neargo.shop.food.service.impl.DiningChannelOrderServiceImpl");
        assertThat(
                        AopUtils.getTargetClass(context.getBean(DiningChannelSourceFactsPort.class))
                                .getName())
                .isEqualTo("ai.neargo.shop.food.commerce.port.FoodChannelSourceFactsPortImpl");
        assertThat(
                        AopUtils.getTargetClass(
                                        context.getBean(ChannelOrderSourceEvidencePort.class))
                                .getName())
                .isEqualTo(
                        "ai.neargo.shop.commerce.service.impl.ChannelOrderSourceEvidencePortImpl");
        var independent = context.getBean("invDataSource", DataSource.class);
        var original = sql.getDataSource();
        assertThat(independent).isNotSameAs(original);
        try (var inv = independent.getConnection();
                var main = original.getConnection()) {
            assertThat(inv.getCatalog()).isNotEqualTo(main.getCatalog());
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(
                    "Independent original Inventory database must be available", failure);
        }
        assertThat(AopUtils.getTargetClass(inventory).getName())
                .startsWith("ai.neargo.shop.inventory.");
    }

    @Test
    void originalOwnersConserveTheInvoiceAndQueryAnUncertainAcceptanceWithoutSendingAgain()
            throws Exception {
        var configured = System.getenv("FOOD_CHANNEL_VERTICAL_REFERENCES");
        var references =
                configured == null || configured.isBlank()
                        ? new FoodChannelVerticalProvisioning(context, MOCK_TOKEN).provision(port)
                        : json.readValue(
                                Files.readString(Path.of(configured)),
                                FoodChannelVerticalFixture.References.class);
        var fixture = new FoodChannelVerticalFixture(sql, json, platform, references, MOCK_TOKEN);
        accept(fixture, fixture.references.happyOrderNo(), false);
        accept(fixture, fixture.references.uncertainOrderNo(), true);
        var cancelled = fixture.references.cancelledOrderNo();
        assertThat(fixture.snapshot(cancelled).document().state().name()).isEqualTo("CANCELLED");
        var key = fixture.key(cancelled);
        var receipt = restaurant.cancel(key);
        assertThat(receipt.cancellationReceiptRef()).isNotBlank();
        assertThat(receipt.coreReceipt()).isNull();
        assertThat(restaurant.prepare(key).binding().status()).isEqualTo("CANCELLED");
        assertThat(restaurant.cancel(key).cancellationReceiptRef())
                .isEqualTo(receipt.cancellationReceiptRef());
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_channel_order_source WHERE entity_no=?"
                                        + " AND store_no=? AND import_no=?",
                                Integer.class,
                                key.entityNo(),
                                key.storeNo(),
                                key.importNo()))
                .isZero();
        assertThat(
                        sql.queryForObject(
                                "SELECT status FROM fnb_channel_import_guard WHERE entity_no=?"
                                        + " AND store_no=? AND import_no=?",
                                String.class,
                                key.entityNo(),
                                key.storeNo(),
                                key.importNo()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void actualFinishedAndBundleSourcesSplitProductionAndHandoverWithoutVirtualParentInventory()
            throws Exception {
        for (String kind : List.of("FINISHED_WITH_MODIFIERS", "BUNDLE_MODIFIERS")) {
            var provisioned =
                    new FoodChannelVerticalProvisioning(context, MOCK_TOKEN).provision(port, kind);
            var fixture =
                    new FoodChannelVerticalFixture(
                            sql, json, platform, provisioned.references(), MOCK_TOKEN);
            var acl = context.getBean(ai.neargo.shop.inventory.service.InventoryAclService.class);
            if ("BUNDLE_MODIFIERS".equals(kind))
                assertThat(acl.itemIdOf("M0001", provisioned.menu().skuNo())).isNull();
            accept(fixture, fixture.references.happyOrderNo(), false, kind);
            if ("BUNDLE_MODIFIERS".equals(kind))
                assertThat(acl.itemIdOf("M0001", provisioned.menu().skuNo())).isNull();
        }
    }

    private void accept(FoodChannelVerticalFixture fixture, String number, boolean uncertain)
            throws Exception {
        accept(fixture, number, uncertain, null);
    }

    private void accept(
            FoodChannelVerticalFixture fixture,
            String number,
            boolean uncertain,
            String expectedBranch)
            throws Exception {
        var refs = fixture.references;
        fixture.scenario(number, uncertain ? Scenario.PERSIST_THEN_TIMEOUT : Scenario.SUCCESS);
        until(
                () -> {
                    imports.recoverDue(100);
                    return "WAITING_DECISION".equals(fixture.order(number).get("import_status"));
                });
        var prepared = restaurant.get(fixture.key(number));
        assertThat(prepared.corePreparation().status().name()).isEqualTo("PREPARED");
        var plan = prepared.corePreparation().inventoryPlan();
        assertThat(plan).isNotNull();
        if (expectedBranch != null)
            assertThat(plan.branches())
                    .anySatisfy(branch -> assertThat(branch.kind()).isEqualTo(expectedBranch));
        var reservation = inventory.query(plan.scope());
        assertThat(reservation.planHash()).isEqualTo(plan.planHash());
        assertThat(reservation.lines())
                .hasSize(plan.branches().stream().mapToInt(b -> b.leaves().size()).sum());
        for (var leaf : plan.branches().stream().flatMap(b -> b.leaves().stream()).toList()) {
            var retained =
                    reservation.lines().stream()
                            .filter(l -> l.leafRef().equals(leaf.leafRef()))
                            .findFirst()
                            .orElseThrow();
            assertThat(retained.originalQty()).isEqualByComparingTo(leaf.quantity());
            assertThat(retained.itemRef()).isEqualTo(leaf.itemRef());
            assertThat(retained.unit()).isEqualTo(leaf.unit());
        }
        var operation =
                orders.accept(
                        refs.entityNo(),
                        refs.storeNo(),
                        number,
                        "SYSTEM",
                        new Accept(
                                orders.getOrder(refs.entityNo(), refs.storeNo(), number)
                                        .order()
                                        .version()),
                        key());
        decisions.recoverDue(100);
        if (uncertain) {
            assertThat(attempts(operation.operationNo())).containsExactly("SEND");
            var current = operations.getOperation(refs.entityNo(), operation.operationNo());
            operations.scheduleRecovery(
                    refs.entityNo(),
                    refs.storeNo(),
                    operation.operationNo(),
                    "SYSTEM",
                    new Schedule(current.version(), "Native vertical original QUERY recovery"),
                    key(),
                    true);
        }
        until(
                () -> {
                    decisions.recoverDue(100);
                    imports.recoverDue(100);
                    var row = fixture.order(number);
                    if (!"CONFIRMED".equals(row.get("import_status"))) {
                        var detail = orders.getOrder(refs.entityNo(), refs.storeNo(), number);
                        if (List.of("PREPARED", "WAITING_CONFIRMATION", "WAITING_OWNER")
                                .contains(detail.order().importStatus()))
                            orders.recoverImport(
                                    refs.entityNo(),
                                    refs.storeNo(),
                                    number,
                                    "SYSTEM",
                                    new Recover(
                                            detail.order().version(),
                                            "Reconcile original native owners"),
                                    key());
                    }
                    return "CONFIRMED".equals(fixture.order(number).get("import_status"));
                });
        assertThat(attempts(operation.operationNo()))
                .isEqualTo(uncertain ? List.of("SEND", "QUERY") : List.of("SEND"));
        var binding = restaurant.binding(fixture.key(number));
        assertThat(binding.status()).isEqualTo("ACCEPTED");
        assertThat(binding.coreOrderNo()).isEqualTo(prepared.corePreparation().orderNo());
        assertThat(
                        sql.queryForObject(
                                "SELECT paid_at FROM ord_order WHERE order_no=?",
                                Object.class,
                                binding.coreOrderNo()))
                .isNull();
        assertThat(binding.coreOrderNo()).isEqualTo(fixture.order(number).get("core_order_ref"));
        var actualCore = core.get(binding.reference());
        assertThat(actualCore.status().name()).isEqualTo("ACCEPTED");
        assertThat(actualCore.quoteNo()).isEqualTo(prepared.corePreparation().quoteNo());
        assertThat(actualCore.inventoryPlan().planNo()).isEqualTo(plan.planNo());
        assertThat(
                        sql.queryForObject(
                                "SELECT quote_ref FROM fnb_order_batch WHERE batch_no=?",
                                String.class,
                                binding.batchNo()))
                .isEqualTo(actualCore.quoteNo());
        assertThat(
                        sql.queryForObject(
                                "SELECT buyer_no FROM fnb_check_cart WHERE cart_no=?",
                                String.class,
                                binding.cartNo()))
                .isNull();
        var command = fixture.command(number);
        long invoice =
                command.original().document().lines().stream().mapToLong(l -> l.netMinor()).sum();
        var allocations =
                orders.getOrder(refs.entityNo(), refs.storeNo(), number).lines().stream()
                        .flatMap(l -> l.allocations().stream())
                        .toList();
        assertThat(allocations).isNotEmpty();
        assertThat(allocations.stream().mapToLong(a -> a.amountMinor()).sum()).isEqualTo(invoice);
        deliverKitchen(binding.batchNo());
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM fnb_kitchen_task t JOIN fnb_kitchen_ticket k"
                                        + " ON k.ticket_no=t.ticket_no WHERE k.batch_no=?",
                                Integer.class,
                                binding.batchNo()))
                .isPositive();
        printAttempt(binding.batchNo(), refs.entityNo(), refs.storeNo());
        produceAndDeliver(fixture, number, binding.batchNo());
        assertThat(restaurant.confirm(fixture.key(number)).orderNo())
                .isEqualTo(binding.coreOrderNo());
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM ord_channel_import WHERE source_system='FOOD'"
                                        + " AND source_no=?",
                                Integer.class,
                                binding.reference().sourceNo()))
                .isEqualTo(1);
    }

    private void printAttempt(String batch, String entity, String store) {
        var owner = context.getBean(PrintService.class);
        var scope = new PrintPort.Scope(entity, store, "SF-M0001");
        String suffix = key().substring(0, 8), device = "VPD" + suffix, template = "VPT" + suffix;
        String station =
                sql.queryForObject(
                        "SELECT t.station_no FROM fnb_kitchen_task t JOIN fnb_kitchen_ticket k ON"
                            + " k.ticket_no=t.ticket_no WHERE k.batch_no=? AND t.task_role='MAKE'"
                            + " AND t.component_no IS NULL",
                        String.class,
                        batch);
        owner.bindDevice(
                scope,
                new PrintPort.DeviceReq(
                        device, "MOCK", "USB", "NATIVE-MOCK-PRINTER", "SF-M0001", "{}", true));
        owner.publishTemplate(
                scope,
                new PrintPort.TemplateReq(template, "VT" + suffix, 1, "KITCHEN", "{{document}}"));
        owner.putRoute(
                scope,
                new PrintPort.RouteReq("VPR" + station, "KITCHEN", station, device, template, 1));
        owner.heartbeat(scope, device, true);
        var jobs =
                context.getBean(FoodPrintService.class)
                        .enqueue(scope, new PrintDocumentReq("KITCHEN", batch, station), key());
        assertThat(jobs).hasSize(1);
        String assignedDevice = jobs.getFirst().deviceNo();
        var claim = owner.claim(scope, assignedDevice);
        assertThat(claim).isNotNull();
        assertThat(claim.jobNo()).isEqualTo(jobs.getFirst().jobNo());
        assertThat(claim.renderedContent()).contains("native channel");
        var retained = owner.get(scope, claim.jobNo());
        assertThat(retained.attempts()).hasSize(1);
        assertThat(retained.attempts().getFirst().attemptNo()).isEqualTo(claim.attemptNo());
        assertThat(retained.attempts().getFirst().status()).isEqualTo("SENT");
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?"
                                        + " AND event_type='PRINT_JOB_CHANGED'",
                                Integer.class,
                                claim.jobNo()))
                .isPositive();
        // The mock device explicitly reports no paper was printed. This releases the real
        // device fence without claiming physical print success between test orders.
        var failed = owner.acknowledge(scope, assignedDevice,
                new PrintPort.Acknowledge(claim.attemptNo(), claim.leaseToken(), "FAILED", null,
                        "NOT_PRINTED"));
        assertThat(failed.status()).isEqualTo("FAILED");
    }

    private void produceAndDeliver(FoodChannelVerticalFixture fixture, String number, String batch)
            throws Exception {
        var f = new FoodChannelVerticalMenuFixture(context, sql, json, port);
        var host = f.original;
        var tasks =
                sql.queryForList(
                        "SELECT t.task_no,t.task_role,t.qty FROM fnb_kitchen_task t JOIN"
                            + " fnb_kitchen_ticket k ON k.ticket_no=t.ticket_no WHERE k.batch_no=?"
                            + " ORDER BY CASE t.task_role WHEN 'MAKE' THEN 0 WHEN 'ASSEMBLE' THEN 1"
                            + " ELSE 2 END",
                        batch);
        for (var task : tasks) {
            String taskNo = (String) task.get("task_no");
            var started =
                    host.post(
                            "/biz/x/food/tasks/" + taskNo + ":start",
                            java.util.Map.of(
                                    "version",
                                    host.taskVersion(taskNo),
                                    "qty",
                                    task.get("qty"),
                                    "reason",
                                    "Native channel preparation"),
                            f.token,
                            key());
            if ("MAKE".equals(task.get("task_role"))) {
                String consumeKey = key();
                var consumed =
                        host.post(
                                "/biz/x/food/production-events/"
                                        + started.path("eventNo").asString()
                                        + ":consume",
                                java.util.Map.of(),
                                f.token,
                                consumeKey);
                assertThat(consumed.path("status").asString()).isIn("CONFIRMED", "NOT_REQUIRED");
                assertThat(
                                host.post(
                                        "/biz/x/food/production-events/"
                                                + started.path("eventNo").asString()
                                                + ":consume",
                                        java.util.Map.of(),
                                        f.token,
                                        consumeKey))
                        .isEqualTo(consumed);
            }
            host.post(
                    "/biz/x/food/tasks/" + taskNo + ":ready",
                    java.util.Map.of(
                            "version",
                            host.taskVersion(taskNo),
                            "qty",
                            task.get("qty"),
                            "reason",
                            "Native channel ready"),
                    f.token,
                    key());
        }
        var originalPlan = restaurant.get(fixture.key(number)).corePreparation().inventoryPlan();
        var phaseReceipt = inventory.query(originalPlan.scope());
        assertThat(phaseReceipt.lines())
                .anySatisfy(line -> assertThat(line.kind()).isEqualTo("RECIPE_MATERIAL"));
        for (var physical : phaseReceipt.lines()) {
            if ("RECIPE_MATERIAL".equals(physical.kind()))
                assertThat(physical.issuedQty()).isEqualByComparingTo(physical.originalQty());
            else {
                assertThat(physical.kind()).isEqualTo("FINISHED_SKU");
                assertThat(physical.issuedQty()).isEqualByComparingTo("0");
            }
        }
        var lines =
                sql
                        .queryForList(
                                "SELECT food_line_no,qty FROM fnb_order_line WHERE batch_no=?",
                                batch)
                        .stream()
                        .map(
                                line ->
                                        java.util.Map.of(
                                                "foodLineNo",
                                                line.get("food_line_no"),
                                                "qty",
                                                line.get("qty")))
                        .toList();
        host.post(
                "/biz/x/food/batches/" + batch + ":confirm-packing",
                java.util.Map.of("lines", lines, "reason", "Native channel packed"),
                f.token,
                key());
        String party =
                sql.queryForObject(
                        "SELECT party_no FROM fnb_channel_order_source WHERE batch_no=?",
                        String.class,
                        batch);
        String handoverKey = key();
        var handoverBody =
                java.util.Map.of(
                        "kind",
                        "DELIVERY",
                        "lines",
                        lines,
                        "recipientNo",
                        party,
                        "recipientRef",
                        fixture.command(number)
                                .original()
                                .document()
                                .customer()
                                .externalCustomerRef(),
                        "reason",
                        "Native original delivery custody");
        var request = json.readValue(json.writeValueAsString(handoverBody),
                ai.neargo.shop.food.dto.KitchenContracts.HandoverReq.class);
        var first = context.getBean(ai.neargo.shop.food.service.KitchenTaskService.class)
                .handover(fixture.references.entityNo(), fixture.references.storeNo(), "SF-M0001", batch, request, handoverKey);
        var handed = json.readTree(json.writeValueAsString(first));
        assertThat(host.post("/biz/x/food/batches/" + batch + ":handover",
                handoverBody, f.token, handoverKey)).isEqualTo(handed);
        assertThat(handed.path("coreDeliveryRef").asString()).isNotBlank();
        assertThat(handed.path("deliveryPending").asBoolean()).isFalse();
        var events =
                sql.queryForList(
                        "SELECT o.event_no FROM fnb_service_event e JOIN fnb_kitchen_task t ON"
                                + " t.task_no=e.task_no JOIN fnb_kitchen_ticket k ON"
                                + " k.ticket_no=t.ticket_no JOIN sys_outbox o ON"
                                + " o.source_system='PLATFORM' AND o.event_type='DINING_SERVICE_EVENT'"
                                + " AND JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.eventNo'))=e.event_no"
                                + " WHERE k.batch_no=? ORDER BY o.stream_key,o.stream_sequence",
                        String.class,
                        batch);
        assertThat(events).isNotEmpty();
        assertThat(sql.queryForObject(
                "SELECT COUNT(*) FROM fnb_service_event e JOIN fnb_kitchen_task t ON"
                        + " t.task_no=e.task_no JOIN fnb_kitchen_ticket k ON k.ticket_no=t.ticket_no"
                        + " WHERE k.batch_no=? AND e.action='CUSTODY_HANDOVER'",
                Integer.class, batch)).isPositive();
        for (String event : events) deliver(event, "commerce-food-fulfillment-events-v1");
        var publications = context.getBean(ChannelFulfillmentService.class);
        var refs = fixture.references;
        until(
                () -> {
                    context.getBean(ChannelFulfillmentServiceImpl.class).recoverDue(100);
                    var links =
                            publications
                                    .listFulfillments(
                                            refs.entityNo(), refs.storeNo(), number, 1, 100)
                                    .records();
                    return !links.isEmpty()
                            && links.stream().allMatch(link -> "SUCCEEDED".equals(link.status()));
                });
        var links =
                publications
                        .listFulfillments(refs.entityNo(), refs.storeNo(), number, 1, 100)
                        .records();
        for (var link : links) {
            assertThat(link.externalEvidenceRef()).isNotBlank();
            var frozen =
                    json.readValue(
                            sql.queryForObject(
                                    "SELECT payload_json FROM cim_fulfillment_link WHERE link_no=?",
                                    String.class,
                                    link.linkNo()),
                            FulfillmentPublication.class);
            var original = context.getBean(MockCommerceGateway.class).queryFulfillment(frozen);
            assertThat(original.status()).isEqualTo(PublicationStatus.CONFIRMED);
            assertThat(original.originalReceiptRef()).isEqualTo(link.externalEvidenceRef());
        }
        var plan = restaurant.get(fixture.key(number)).corePreparation().inventoryPlan();
        assertThat(inventory.query(plan.scope()).lines())
                .allSatisfy(
                        line ->
                                assertThat(line.issuedQty())
                                        .isEqualByComparingTo(line.originalQty()));
    }

    private void deliver(String event, String handler) throws Exception {
        long id =
                sql.queryForObject(
                        "SELECT id FROM sys_outbox_delivery WHERE event_no=? AND handler_id=?",
                        Long.class,
                        event,
                        handler);
        var facade = context.getBean(UnifiedOutboxFacade.class);
        var source = facade.store("PLATFORM");
        var claim =
                source.tryClaim(
                                new DeliveryRef("PLATFORM", id),
                                key(),
                                Instant.now(),
                                Duration.ofMinutes(2))
                        .orElseThrow();
        var result = facade.handler("PLATFORM", handler).handle(claim.event());
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(source.complete(claim, result, Instant.now(), RetryPolicy.defaults())).isTrue();
    }

    private void deliverKitchen(String batch) throws Exception {
        var event =
                sql.queryForObject(
                        "SELECT event_no FROM sys_outbox WHERE aggregate_id=?"
                                + " AND event_type='DINING_ORDER_ACCEPTED'",
                        String.class,
                        batch);
        long id =
                sql.queryForObject(
                        "SELECT id FROM sys_outbox_delivery WHERE event_no=? AND handler_id=?",
                        Long.class,
                        event,
                        FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER);
        var facade = context.getBean(UnifiedOutboxFacade.class);
        var source = facade.store("PLATFORM");
        var claim =
                source.tryClaim(
                                new DeliveryRef("PLATFORM", id),
                                key(),
                                Instant.now(),
                                Duration.ofMinutes(2))
                        .orElseThrow();
        var result =
                facade.handler("PLATFORM", FoodOutboxRouting.KITCHEN_ACCEPTED_HANDLER)
                        .handle(claim.event());
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.SENT);
        assertThat(source.complete(claim, result, Instant.now(), RetryPolicy.defaults())).isTrue();
    }

    private List<String> attempts(String operation) {
        return sql.queryForList(
                "SELECT attempt_kind FROM cim_operation_attempt WHERE operation_no=? ORDER BY"
                        + " attempt_no",
                String.class,
                operation);
    }

    private static void until(BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline))
                throw new AssertionError("Original native workflow did not converge");
            Thread.sleep(250);
        }
    }

    private static Path mockDirectory() {
        String supplied = System.getenv("FOOD_CHANNEL_VERTICAL_MOCK_DIRECTORY");
        if (supplied != null && !supplied.isBlank()) return Path.of(supplied);
        try {
            return Files.createTempDirectory("food-channel-vertical-");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
