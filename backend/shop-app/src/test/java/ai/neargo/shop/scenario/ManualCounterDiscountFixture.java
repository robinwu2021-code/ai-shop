package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Composes real common-owner setup; it has no inherited test methods or successful owner substitutes. */
final class ManualCounterDiscountFixture {
    private final CheckoutFlowFixture flow;
    private final ManualServiceAdmissionPort manual;
    private final GuestRegistrationPort guests;
    final String member;

    ManualCounterDiscountFixture(CheckoutFlowFixture flow, ManualServiceAdmissionPort manual,
            GuestRegistrationPort guests) {
        this.flow = flow;
        this.manual = manual;
        this.guests = guests;
        member = "member-" + flow.sku;
        var jdbc = flow.jdbc;
        jdbc.update("INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,"
                + "created_at,updated_at) VALUES (?,?,'BUSINESS_LICENSE','discount license','FIXTURE','VALID',"
                + "NOW(),NOW())", "license-" + flow.sku, flow.entity);
        String person = "person-" + flow.sku;
        jdbc.update("INSERT INTO usr_person(person_no,phone_hash,user_no,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',NOW(),NOW())", person, CheckoutDigests.hash(person), flow.buyer);
        jdbc.update("INSERT INTO mbr_member(member_no,entity_no,person_no,status,source,joined_at,"
                + "created_at,updated_at) VALUES (?,?,?,'ACTIVE','MANUAL',?,NOW(),NOW())",
                member, flow.entity, person, System.currentTimeMillis());
        jdbc.update("UPDATE prd_sku SET price=10000 WHERE sku_no=?", flow.sku);
    }

    CounterCustomerRef user() {
        return new CounterCustomerRef("USER", member, null, null);
    }

    CounterCustomerRef guest() {
        var registration = flow.asMerchant(() -> guests.register(new GuestRegistrationPort.Scope(flow.entity,
                flow.store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", flow.actor)),
                new GuestRegistrationPort.RegisterCommand(null, null, true, "discount-" + flow.sku)));
        return new CounterCustomerRef("GUEST", null, registration.guestNo(), registration.claimToken());
    }

    PrepareCounterReq request(String suffix, CounterCustomerRef customer, Map<String, String> choices) {
        return new PrepareCounterReq(new OperationRef("BEAUTY", suffix + '-' + flow.sku),
                "prepare-" + suffix + '-' + flow.sku, customer, 1, "a".repeat(64),
                List.of(new CheckoutLineReq("service-line", flow.sku, 1)), null, choices, null);
    }

    PrepareCounterReq twoLineRequest(String suffix, CounterCustomerRef customer) {
        String secondSku = "second-" + suffix + '-' + flow.sku;
        flow.jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,"
                + "created_at,updated_at) VALUES (?,?,?,'CN',10000,100,0,NOW(),NOW())",
                secondSku, flow.goods, flow.entity);
        var catalog = flow.catalogs.read(flow.entity, flow.store, flow.testClock.instant());
        var entries = new ArrayList<>(catalog.definition().entries());
        entries.add(new StoreSalesCatalogPort.Entry(secondSku, null, 1, true,
                StoreSalesCatalogPort.SalesMode.UNRESTRICTED, List.of()));
        flow.asMerchant(() -> flow.catalogs.publish(flow.entity, flow.store, flow.actor,
                new StoreSalesCatalogPort.PublishReq(catalog.headRevision(),
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), catalog.definition().timezone(),
                        catalog.definition().groups(), entries), "two-line-catalog-" + suffix + '-' + flow.sku));
        return new PrepareCounterReq(new OperationRef("BEAUTY", suffix + '-' + flow.sku),
                "prepare-" + suffix + '-' + flow.sku, customer, 1, "a".repeat(64),
                List.of(new CheckoutLineReq("service-line-one", flow.sku, 1),
                        new CheckoutLineReq("service-line-two", secondSku, 1)), null, Map.of(), null);
    }

    CounterCheckoutQuote prepare(String suffix, CounterCustomerRef customer, Map<String, String> choices) {
        return prepare(request(suffix, customer, choices));
    }

    CounterCheckoutQuote prepare(PrepareCounterReq request) {
        return flow.asMerchant(() -> manual.prepareCounter(flow.scope, request));
    }

    CounterConfirmation accept(CounterCheckoutQuote quote) {
        return flow.asMerchant(() -> manual.acceptCounter(flow.scope, new AcceptCounterReq(quote.quote().operation(),
                "accept-" + quote.quote().operation().operationNo(), quote.quote().quoteNo(),
                quote.quote().quoteRevision(),
                quote.quote().quoteHash(), quote.party().identityRevision(),
                flow.testClock.instant().truncatedTo(ChronoUnit.MILLIS), "MERCHANT_RECORDED_IN_PERSON")));
    }

    CheckoutResult place(CounterConfirmation confirmation) {
        return flow.asMerchant(() -> manual.placeCounter(flow.scope, placeRequest(confirmation)));
    }

    static PlaceCounterReq placeRequest(CounterConfirmation confirmation) {
        return new PlaceCounterReq(confirmation.operation(), confirmation.confirmationNo(),
                "place-" + confirmation.operation().operationNo());
    }

    ConfirmManualReceipt receipt(CounterCheckoutQuote quote, CheckoutResult created) {
        long version = flow.jdbc.queryForObject("SELECT version FROM ord_order WHERE order_no=?",
                Long.class, created.orderNo());
        return new ConfirmManualReceipt(quote.quote().operation(), "cash-" + created.orderNo(), version,
                ManualMethod.CASH, quote.quote().dueMinor(), "CNY",
                flow.testClock.instant().truncatedTo(ChronoUnit.MILLIS), null);
    }

    String campaign(String suffix, long threshold, long discount) {
        return campaign(suffix, threshold, discount, "MAIN", flow.entity, flow.store);
    }

    String campaign(String suffix, long threshold, long discount, String tenant, String entity, String store) {
        String no = suffix + '-' + flow.sku;
        flow.jdbc.update("INSERT INTO mkt_campaign(campaign_no,entity_no,store_no,type,name,status,"
                + "threshold_minor,discount_minor,start_at,end_at,version,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,?,'FULL_CUT',?,'RUNNING',?,?,?,?,0,?,NOW(),NOW())", no, entity,
                store, suffix, threshold, discount, System.currentTimeMillis() - 60000,
                System.currentTimeMillis() + 3600000, tenant);
        return no;
    }

    String promotion(String suffix, String benefit, long threshold, long amount) {
        return promotion(suffix, benefit, threshold, amount, "MERCHANT", flow.entity);
    }

    String promotion(String suffix, String benefit, long threshold, long amount, String owner, String entity) {
        String no = suffix + '-' + flow.sku;
        flow.jdbc.update("INSERT INTO pmt_activity(activity_no,entity_no,store_no,owner,name,trigger_type,"
                + "trigger_amount_minor,benefit_type,benefit_amount_minor,schedule_type,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,?,?,'AMOUNT',?,?,?,'ALWAYS_ON','RUNNING',"
                + "'MAIN',NOW(),NOW())", no, entity, flow.store, owner, suffix, threshold, benefit, amount);
        return no;
    }

    void mandatory(String suffix, String type) {
        String no = campaign(suffix, 0, 1000);
        flow.jdbc.update("UPDATE mkt_campaign SET type=?,goods_nos=?,flash_price_minor=8000,buy_n=1,gift_m=1"
                + " WHERE campaign_no=?", type, "[\"" + flow.goods + "\"]", no);
    }

    void assertCreated(CounterCheckoutQuote quote, CheckoutResult created) {
        assertThat(created.phase()).isEqualTo("CREATED");
        assertThat(flow.jdbc.queryForMap("SELECT goods_amount,discount_amount,pay_amount,pay_channel,pay_trade_no"
                + " FROM ord_order WHERE order_no=?", created.orderNo()))
                .containsEntry("goods_amount", quote.quote().goodsMinor())
                .containsEntry("discount_amount", quote.quote().discountMinor())
                .containsEntry("pay_amount", quote.quote().dueMinor())
                .containsEntry("pay_channel", null).containsEntry("pay_trade_no", null);
        assertThat(flow.jdbc.queryForObject("SELECT discount_merchant FROM ord_sub_order WHERE sub_order_no=?",
                Long.class, created.subOrderNo())).isEqualTo(quote.quote().discountMinor());
        assertThat(flow.jdbc.queryForObject("SELECT paid_share_minor FROM ord_item WHERE sub_order_no=?",
                Long.class, created.subOrderNo())).isEqualTo(quote.quote().dueMinor());
        assertThat(flow.jdbc.queryForObject("SELECT COUNT(*) FROM ord_checkout_payment_request WHERE operation_no=?",
                Long.class, quote.quote().operation().operationNo())).isZero();
        assertThat(flow.jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE order_no=?",
                Long.class, created.orderNo())).isZero();
    }

    void assertAdmitted(CounterCheckoutQuote quote, CheckoutResult created) {
        var receipt = receipt(quote, created);
        var actual = flow.asMerchant(() -> manual.confirm(flow.scope, receipt));
        assertThat(actual.admissionState()).isEqualTo("ADMITTED");
        assertThat(flow.asMerchant(() -> manual.confirm(flow.scope, receipt)).manualDeclarationNo())
                .isEqualTo(actual.manualDeclarationNo());
        var source = flow.asMerchant(() -> manual.source(flow.scope,
                new SourceLookup(quote.quote().operation(), created.subOrderNo())));
        assertThat(source.party()).isEqualTo(quote.party());
        assertThat(source.lines()).hasSize(1);
        assertThat(source.lines().getFirst().availableQuantity()).isEqualTo(1);
        assertThat(source.orderNo()).isEqualTo(created.orderNo());
        assertThat(flow.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id IN (?,?)"
                + " AND event_type IN ('ORDER_PAID','SUB_ORDER_PAID')", Long.class,
                created.orderNo(), created.subOrderNo())).isZero();
        assertThat(flow.jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?"
                + " AND event_type='MANUAL_SERVICE_RECEIPT_RECORDED'", Long.class, created.orderNo())).isEqualTo(1);
    }

    /**
     * Phase-B-only producer, extracted verbatim by C and compiled with genuine Task66 old owner sources.
     * Arguments: USER or GUEST, fresh output directory. System property task68.old.classes pins old code origin.
     * C supplies the native schema/environment; bootstrap below seeds real owners, catalog and customer inputs.
     * No old owner is built, loaded or invoked in phase A. No JSON/intent/hash/reservation is manufactured.
     */
    public static final class OldPendingProducer {
        public record Input(CheckoutScope scope, PrepareCounterReq prepare) {}

        /** Start only the old source-built application, reserve once for real, then lose the outside-root reply. */
        public static void main(String[] args) throws Exception {
            if (args.length != 2 || !List.of("USER", "GUEST").contains(args[0])) {
                throw new IllegalArgumentException("old producer requires USER/GUEST and a fresh output directory");
            }
            var output = Path.of(args[1]);
            Files.createDirectory(output);
            var lostRealReserve = new AtomicBoolean();
            var reservation = new AtomicReference<StockDispositionPort.ReservationView>();
            var app = new SpringApplication(ShopApplication.class, CheckoutFlowFixture.ClockConfig.class);
            app.setAdditionalProfiles("test");
            app.setDefaultProperties(Map.of("shop.member-code.enabled", "true",
                    "shop.catalog.publication.archive-only", "true",
                    "shop.member.guest.credential-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
            app.addInitializers(context -> context.addBeanFactoryPostProcessor(factory ->
                    factory.addBeanPostProcessor(new BeanPostProcessor() {
                        @Override
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (!(bean instanceof StockDispositionPort)) {
                                return bean;
                            }
                            var actual = spy((StockDispositionPort) bean);
                            doAnswer(call -> {
                                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                                    throw new AssertionError("old reservation crossed an owner root");
                                }
                                var result = (StockDispositionPort.ReservationView) call.callRealMethod();
                                if (!"RESERVED".equals(result.outcome()) || result.lines().isEmpty()
                                        || !lostRealReserve.compareAndSet(false, true)) {
                                    throw new AssertionError("old producer did not reserve exactly once for real");
                                }
                                reservation.set(result);
                                throw new IllegalStateException("phase-B lost real original reservation reply");
                            }).when(actual).reserveOriginal(any());
                            doAnswer(call -> {
                                if (lostRealReserve.get()) {
                                    throw new IllegalStateException("phase-B pause before final order write");
                                }
                                return call.callRealMethod();
                            }).when(actual).observeReservation(any());
                            return actual;
                        }
                    })));
            try (ConfigurableApplicationContext context = app.run()) {
                var json = context.getBean(ObjectMapper.class);
                var manual = context.getBean(ManualServiceAdmissionPort.class);
                var bootstrap = new Bootstrap();
                context.getAutowireCapableBeanFactory().autowireBean(bootstrap);
                bootstrap.fixture();
                var real = new ManualCounterDiscountFixture(bootstrap, manual,
                        context.getBean(GuestRegistrationPort.class));
                var customer = "USER".equals(args[0]) ? real.user() : real.guest();
                var input = new Input(bootstrap.scope, real.request("old-pending", customer, Map.of()));
                Files.writeString(output.resolve("input.json"), json.writeValueAsString(input));
                String origin = AopUtils.getTargetClass(manual).getProtectionDomain().getCodeSource()
                        .getLocation().toURI().getPath();
                if (!Path.of(System.getProperty("task68.old.classes")).toRealPath()
                        .equals(Path.of(origin).toRealPath())) {
                    throw new AssertionError("old common owner code origin is not the C-pinned Task66 build");
                }
                var jdbc = context.getBean(JdbcTemplate.class);
                var tokens = context.getBean(TokenStores.class);
                cashier(tokens, input.scope().actorNo(), () -> {
                    var quote = manual.prepareCounter(input.scope(), input.prepare());
                    if (quote.quote().discountMinor() != 0 || quote.quote().dueMinor() != quote.quote().goodsMinor()) {
                        throw new AssertionError("genuine old owner must produce its own zero quote");
                    }
                    bootstrap.testClock.at = Instant.now();
                    var accepted = manual.acceptCounter(input.scope(), new AcceptCounterReq(quote.quote().operation(),
                            "old-accept-" + quote.quote().operation().operationNo(), quote.quote().quoteNo(), 1,
                            quote.quote().quoteHash(), quote.party().identityRevision(),
                            bootstrap.testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                            "MERCHANT_RECORDED_IN_PERSON"));
                    var place = new PlaceCounterReq(accepted.operation(), accepted.confirmationNo(),
                            "old-place-" + accepted.operation().operationNo());
                    var pending = manual.placeCounter(input.scope(), place);
                    if (!lostRealReserve.get() || !"PLACING".equals(pending.phase())) {
                        throw new AssertionError("genuine old owner did not stop at its reserved pending intent");
                    }
                    var intent = jdbc.queryForMap("SELECT * FROM ord_create_intent WHERE operation_no=?",
                            accepted.operation().operationNo());
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Long.class,
                            intent.get("pay_no")) != 0) {
                        throw new AssertionError("old final order already exists");
                    }
                    String pricing = jdbc.queryForObject("SELECT pricing_json FROM ord_checkout_quote WHERE quote_no=?",
                            String.class, quote.quote().quoteNo());
                    if (json.readTree(pricing).has("counterActivitySeal") || json.readTree(pricing).has("economic")) {
                        throw new AssertionError("old producer loaded a current counter writer");
                    }
                    var evidence = new LinkedHashMap<String, Object>();
                    evidence.put("baselineCommit", "2c0708f752d747d88a9eda7478326a964d1984fa");
                    evidence.put("ownerCodeOrigin", origin);
                    evidence.put("scope", input.scope());
                    evidence.put("prepare", input.prepare());
                    evidence.put("quote", quote);
                    evidence.put("confirmation", accepted);
                    evidence.put("place", place);
                    evidence.put("intent", intent);
                    evidence.put("reservationObservation", reservation.get());
                    evidence.put("pricingJson", pricing);
                    evidence.put("confirmationRow", jdbc.queryForMap(
                            "SELECT * FROM ord_checkout_confirmation WHERE confirmation_no=?",
                            accepted.confirmationNo()));
                    try {
                        Files.writeString(output.resolve("old-pending.json"), json.writeValueAsString(evidence));
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return null;
                });
            }
        }

        /** Reuses only the genuine Task66 setup helper, never any historical @Test method. */
        static final class Bootstrap extends CheckoutFlowFixture {
            @Override
            protected <T> T asMerchant(Supplier<T> work) {
                return cashier(tokens, actor, work);
            }
        }

        private static <T> T cashier(TokenStores tokens, String actor, Supplier<T> work) {
            var user = LoginUser.merchant(actor, "old pending cashier");
            var pool = tokens.of(Realm.MERCHANT);
            String token = pool.issue(new SessionData(user));
            var original = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
            auth.setDetails(token);
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            try {
                return work.get();
            } finally {
                SecurityContextHolder.setContext(original);
                pool.revoke(token);
            }
        }
    }
}
