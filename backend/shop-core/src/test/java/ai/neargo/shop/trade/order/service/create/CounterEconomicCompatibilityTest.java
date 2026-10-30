package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PromotionOption;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.trade.service.OrderService.CreateOrderCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Baseline production and every test in this class remain unexecuted until unified phase B. */
public class CounterEconomicCompatibilityTest {
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final CounterEconomicCodec codec = new CounterEconomicCodec(json);
    private static final CustomerParty USER = new CustomerParty("USER", "U1", null, (Long) null);
    private static final CustomerParty GUEST = new CustomerParty("GUEST", null, "GUEST1", 7L);
    private static final List<CheckoutLineReq> LINES = List.of(new CheckoutLineReq("L1", "SKU1", 1));

    @Test
    void exactOldConstructorAndGenuineGoldenBytesRoundtrip() throws IOException {
        var hashes = json.readTree(resource("hashes.json"));
        for (String name : List.of("ordinary-v1", "m1-v1", "m2a-user-v1", "m2a-guest-v1")) {
            String frozen = resource(name + ".json");
            assertThat(CheckoutDigests.hash(frozen)).isEqualTo(hashes.get(name + ".json").asString());
            var economic = json.readValue(frozen, CheckoutEconomicSnapshot.class);
            assertThat(json.writeValueAsString(economic)).isEqualTo(frozen);
            assertThat(economic.quoteHash()).isEqualTo(hashes.get(name + ".quoteHash").asString());
            assertThat(economic.counterActivitySeal()).isNull();
            assertThat(frozen).doesNotContain("counterActivitySeal");
            if (name.startsWith("m2a")) {
                assertThat(codec.readQuoteSnapshot(frozen).schema()).isZero();
                assertThat(codec.readQuoteSnapshot(frozen).promotions()).isEmpty();
                assertThat(codec.legacyZero(economic, command())).isTrue();
                codec.requireQuoteHash(name.contains("guest") ? GUEST : USER, LINES, economic, economic.quoteHash());
            }
        }
        String frozenPlan = resource("m2a-pending-plan-v1.json");
        var plan = codec.readPlan(frozenPlan);
        assertThat(codec.writePlan(plan)).isEqualTo(frozenPlan);
        assertThat(CheckoutDigests.hash(frozenPlan)).isEqualTo(hashes.get("m2a-pending-plan-v1.json").asString());
        assertThat(codec.planHash(plan)).isEqualTo(hashes.get("planHash").asString());
        assertThat(json.writeValueAsString(oldMoney(null, USER, true))).doesNotContain("counterActivitySeal");
    }

    @Test
    void schemaOneEnvelopeAndBarePlanKeepDisplayOutsideAuthority() {
        var economic = sealed(USER, Map.of("E1", "A1"));
        var original = snapshot(economic, "Original");
        var renamed = snapshot(economic, "Renamed");
        assertThat(codec.readQuoteSnapshot(codec.writeQuote(original))).isEqualTo(original);
        assertThat(codec.readQuote(codec.writeQuote(renamed))).isEqualTo(economic);
        assertThat(codec.planHash(plan(economic))).isEqualTo(codec.planHash(plan(renamed.economic())));
        assertThat(codec.writePlan(plan(economic))).doesNotContain("promotions");
        var changedOptions = new CounterQuoteSnapshot(1, economic,
                List.of(option("A2", "new competing title"), option("A1", "new selected title"), none()));
        assertThat(codec.readQuote(codec.writeQuote(changedOptions)).quoteHash()).isEqualTo(economic.quoteHash());
        assertThat(codec.planHash(plan(changedOptions.economic()))).isEqualTo(codec.planHash(plan(economic)));
        codec.requireQuoteHash(USER, LINES, economic, economic.quoteHash());
        assertThatThrownBy(() -> codec.readQuoteSnapshot(json.writeValueAsString(economic)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codec.readPlan(codec.writeQuote(original))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codec.readQuoteSnapshot(codec.writePlan(plan(economic))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void schemaOnePlansPreserveExplicitPartyAndRejectContradictoryCommand() {
        for (CustomerParty party : List.of(USER, GUEST)) {
            var economic = sealed(party, Map.of("E1", "A1"));
            var original = plan(economic);
            assertThat(original.party()).isEqualTo(party);
            assertThat(original.command().activityChoices()).containsExactlyEntriesOf(Map.of("E1", "A1"));
            var decoded = codec.readPlan(codec.writePlan(original));
            assertThat(decoded).isEqualTo(original);
            assertThat(codec.planHash(decoded)).isEqualTo(CheckoutDigests.hash(json, original));
            var contradictory = new CounterOrderPlan(original.orderNo(), original.subOrderNo(), original.operationNo(),
                    original.intentKey(), original.requestHash(), party, command(), economic, original.stockNeeds(),
                    original.stockAuthority(), original.lifecycleVersion(), original.manifestHash(),
                    original.intentRevision());
            assertThatThrownBy(() -> codec.planHash(contradictory))
                    .isInstanceOfSatisfying(BizException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        }
    }

    @Test
    void newZeroAndNoneCarrySealsAndOnlyTheirFrozenNoneOption() {
        for (Map<String, String> choices : List.of(Map.<String, String>of(), Map.of("E1", "NONE"))) {
            var zero = sealedZero(USER, choices);
            var snapshot = new CounterQuoteSnapshot(1, zero, List.of(none()));
            var roundtrip = codec.readQuoteSnapshot(codec.writeQuote(snapshot));
            assertThat(roundtrip).isEqualTo(snapshot);
            assertThat(roundtrip.economic().counterActivitySeal()).isNotNull();
            assertThat(roundtrip.promotions()).containsExactly(none());
            assertThat(codec.legacyZero(zero, command())).isFalse();
        }
    }

    @Test
    void oldScopeConstructorsKeepTheirLaneAndTrustedPoliciesRejectMixedContext() {
        var oldOrdinary = new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null);
        var oldManual = new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null,
                "MANUAL_OFFLINE", "OP1");
        var oldCounter = new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null,
                "MANUAL_OFFLINE", "OP1", USER, "MERCHANT_RECORDED_IN_PERSON");
        assertThat(oldOrdinary.manual()).isFalse();
        assertThat(oldManual.manual()).isTrue();
        assertThat(oldCounter.counter()).isTrue();
        assertThat(oldCounter.counterPolicy()).isNull();
        assertThat(oldCounter.originalCounterEconomic()).isNull();
        assertThatThrownBy(() -> new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null,
                "MANUAL_OFFLINE", "OP1", USER, "MERCHANT_RECORDED_IN_PERSON",
                CounterEconomicCodec.CounterPolicy.FROZEN_M2A_ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null,
                "MANUAL_OFFLINE", "OP1", USER, "MERCHANT_RECORDED_IN_PERSON",
                CounterEconomicCodec.CounterPolicy.CURRENT_FULL_CUT, oldMoney(null, USER, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPricingScope("MAIN", "E1", "S1", 1, "scope", LINES, null,
                "MANUAL_OFFLINE", "OP1", null, "CONSUMER_SESSION",
                CounterEconomicCodec.CounterPolicy.CURRENT_FULL_CUT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictRawShapeRejectsUnknownMissingNullDuplicateAndCoercedFields() {
        String valid = codec.writeQuote(snapshot(sealed(USER, Map.of()), "Name"));
        for (String field : List.of("schema", "economic", "promotions")) {
            ObjectNode missing = (ObjectNode) json.readTree(valid);
            missing.remove(field);
            assertConflict(missing);
            ObjectNode explicitNull = (ObjectNode) json.readTree(valid);
            explicitNull.putNull(field);
            assertConflict(explicitNull);
        }
        ObjectNode unknown = (ObjectNode) json.readTree(valid);
        unknown.put("unexpected", 1);
        assertConflict(unknown);
        ObjectNode badSeal = (ObjectNode) json.readTree(valid);
        ((ObjectNode) badSeal.get("economic")).putNull("counterActivitySeal");
        assertConflict(badSeal);
        ObjectNode unknownPolicy = (ObjectNode) json.readTree(valid);
        ((ObjectNode) unknownPolicy.get("economic").get("counterActivitySeal")).put("policy", "UNKNOWN");
        assertConflict(unknownPolicy);
        ObjectNode coercion = (ObjectNode) json.readTree(valid);
        ((ObjectNode) coercion.get("economic")).put("goodsMinor", "10000");
        assertConflict(coercion);
        assertThatThrownBy(() -> codec.readQuoteSnapshot(valid.replaceFirst("\\{", "{\"schema\":1,")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codec.readQuoteSnapshot(valid + " {}" )).isInstanceOf(BizException.class);
        ObjectNode unknownSchema = (ObjectNode) json.readTree(valid);
        unknownSchema.put("schema", 0);
        assertConflict(unknownSchema);
        ObjectNode nestedUnknown = (ObjectNode) json.readTree(valid);
        ((ObjectNode) nestedUnknown.get("economic").get("counterActivitySeal").get("selectedRules").get(0))
                .put("extraOwnerFact", "forbidden");
        assertConflict(nestedUnknown);
        ObjectNode missingSealField = (ObjectNode) json.readTree(valid);
        ((ObjectNode) missingSealField.get("economic").get("counterActivitySeal")).remove("selectedRules");
        assertConflict(missingSealField);
        ObjectNode missingSeal = (ObjectNode) json.readTree(valid);
        ((ObjectNode) missingSeal.get("economic")).remove("counterActivitySeal");
        assertConflict(missingSeal);
        assertThatThrownBy(() -> codec.readQuoteSnapshot(json.writeValueAsString(oldMoney(null, USER, true))
                .replace("\"activity\":", "\"counterActivitySeal\":null,\"activity\":")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void optionsAndSelectedEconomicsCannotContradictFrozenSeal() {
        var economic = sealed(USER, Map.of());
        for (List<PromotionOption> options : List.of(List.<PromotionOption>of(),
                List.of(none()), List.of(none(), option("A1", "Name")),
                List.of(option("A1", "Name"), option("A1", "Name"), none()),
                List.of(new PromotionOption("A1", "ACTIVITY", "Name", false, null), none()))) {
            assertThatThrownBy(() -> codec.writeQuote(new CounterQuoteSnapshot(1, economic, options)))
                    .isInstanceOf(BizException.class);
        }
        var tree = (ObjectNode) json.readTree(codec.writeQuote(snapshot(economic, "Name")));
        ((ObjectNode) tree.get("economic").get("activity").get("applied").get(0)).put("name", "hidden");
        assertConflict(tree);
        assertThatThrownBy(() -> codec.requireQuoteHash(GUEST, LINES, economic, economic.quoteHash()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codec.requireQuoteHash(USER, List.of(new CheckoutLineReq("L2", "SKU1", 1)),
                economic, economic.quoteHash())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> codec.requireQuoteHash(USER, LINES, economic, "0".repeat(64)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void frozenOwnerAliasesRoundtripAndLegacyCannotCarryPositiveMoney() {
        var money = sealed(GUEST, Map.of("S1", "A1"));
        var decoded = codec.readQuote(codec.writeQuote(snapshot(money, null)));
        assertThat(decoded.counterActivitySeal().selectedRules().getFirst().entityNo()).isEqualTo("e1 ");
        assertThat(decoded.activity().shares().getFirst().merchantNo()).isEqualTo("E1");
        assertThat(codec.legacyZero(decoded, command())).isFalse();
        var tree = (ObjectNode) json.valueToTree(decoded);
        tree.remove("counterActivitySeal");
        assertThatThrownBy(() -> codec.readQuoteSnapshot(json.writeValueAsString(tree)))
                .isInstanceOf(BizException.class);
    }

    private void assertConflict(ObjectNode tree) {
        assertThatThrownBy(() -> codec.readQuoteSnapshot(json.writeValueAsString(tree)))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private CheckoutEconomicSnapshot sealed(CustomerParty party, Map<String, String> choices) {
        var group = new CampaignPort.MerchantAmount("E1", 10000, 1, "S1",
                List.of(new CampaignPort.GoodsLine("G1", 10000, 1)), false);
        var rule = new CampaignPort.CounterRule("main ", "A1", "e1 ", "s1 ", 0,
                "FULL_CUT", "RUNNING", 0, null, 1000L, 100L, 200L, List.of(), 1000, "MERCHANT", 0);
        var seal = CounterCampaignContract.seal(json, party, List.of(group), choices, List.of(rule));
        var activity = new CampaignPort.Discount(1000, List.of(new CampaignPort.MerchantDiscount("E1", 1000, "S1")),
                List.of(new CampaignPort.AppliedActivity("A1", "E1", 1000, 1, 0, null, null, "S1")));
        var evidence = evidence(choices);
        var raw = new CheckoutEconomicSnapshot(null, evidence, party.userNo(), "E1", "S1", "CNY", "OFFLINE",
                "STORE_VERIFY", "SERVICE_EXECUTION", quoteLines(1000), 10000, 0, 1000, 9000, 0, 1000,
                activity, CouponPort.Allocation.none(), seal);
        return copyHash(raw, counterHash(json, party, raw));
    }

    private CheckoutEconomicSnapshot sealedZero(CustomerParty party, Map<String, String> choices) {
        var group = new CampaignPort.MerchantAmount("E1", 10000, 1, "S1",
                List.of(new CampaignPort.GoodsLine("G1", 10000, 1)), false);
        var seal = CounterCampaignContract.seal(json, party, List.of(group), choices, List.of());
        var raw = new CheckoutEconomicSnapshot(null, evidence(choices), party.userNo(), "E1", "S1", "CNY", "OFFLINE",
                "STORE_VERIFY", "SERVICE_EXECUTION", quoteLines(0), 10000, 0, 0, 10000, 0, 0,
                CampaignPort.Discount.none(), CouponPort.Allocation.none(), seal);
        return copyHash(raw, counterHash(json, party, raw));
    }

    private CounterQuoteSnapshot snapshot(CheckoutEconomicSnapshot money, String title) {
        return new CounterQuoteSnapshot(1, money, List.of(option("A1", title), none()));
    }

    private static PromotionOption option(String id, String title) {
        return new PromotionOption(id, "ACTIVITY", title, true, null);
    }

    private static PromotionOption none() {
        return option("NONE", null);
    }

    private static CheckoutEconomicSnapshot.Evidence evidence(Map<String, String> choices) {
        return evidence(choices, "OFFLINE");
    }

    private static CheckoutEconomicSnapshot.Evidence evidence(Map<String, String> choices, String payMode) {
        var mapper = JsonMapper.builder().build();
        return new CheckoutEconomicSnapshot.Evidence("MAIN", 1, "scope", CheckoutDigests.hash(mapper,
                Arrays.asList(LINES, null, new TreeMap<>(choices), payMode)), "CAT1", 1, "catalog");
    }

    private static List<QuoteLine> quoteLines(long discount) {
        return List.of(new QuoteLine("L1", "SKU1", "G1", 1, "COUNT", 10000, 10000, discount,
                10000 - discount, "product"));
    }

    private static CheckoutEconomicSnapshot oldMoney(String hash, CustomerParty party, boolean manual) {
        return new CheckoutEconomicSnapshot(hash, evidence(Map.of(), manual ? "OFFLINE" : "ONLINE"),
                party.userNo(), "E1", "S1", "CNY",
                manual ? "OFFLINE" : "ONLINE", "STORE_VERIFY", "SERVICE_EXECUTION", quoteLines(0),
                10000, 0, 0, 10000, 0, 0, CampaignPort.Discount.none(), CouponPort.Allocation.none());
    }

    private static CheckoutEconomicSnapshot copyHash(CheckoutEconomicSnapshot m, String hash) {
        return new CheckoutEconomicSnapshot(hash, m.evidence(), m.buyerNo(), m.entityNo(), m.storeNo(), m.currency(),
                m.payMode(), m.fulfillment(), m.fulfillmentAuthority(), m.lines(), m.goodsMinor(), m.freightMinor(),
                m.discountMinor(), m.dueMinor(), m.platformMinor(), m.merchantMinor(), m.activity(), m.coupon(),
                m.counterActivitySeal());
    }

    private static String counterHash(ObjectMapper mapper, CustomerParty party, Object money) {
        return CheckoutDigests.hash(mapper, Arrays.asList("COUNTER_MANUAL_QUOTE_V1", party,
                Arrays.asList("MAIN", "E1", "S1", 1L, "scope", LINES, "CAT1", 1L, "catalog"), money));
    }

    private static CreateOrderCommand command() {
        return new CreateOrderCommand(List.of(new CreateOrderCommand.Item(null, "SKU1", 1)), "STORE_VERIFY",
                null, null, null, 0L, null, null, "OFFLINE", null, null, null, false, Map.of(),
                Map.of("E1", "S1"), Map.of());
    }

    private static CounterOrderPlan plan(CheckoutEconomicSnapshot money) {
        var seal = money.counterActivitySeal();
        var party = seal == null ? USER : seal.party();
        var originalCommand = seal == null ? command() : command().withChoices(seal.requestedChoices(), null);
        return new CounterOrderPlan("O1", "SO1", "OP1", "INTENT1", "request", party, originalCommand, money,
                List.of(new StockPort.SkuQty("SKU1", 1, "S1", "SO1", 1)), "LOCAL", 1, "manifest", 1);
    }

    private static String resource(String name) throws IOException {
        try (var stream = CounterEconomicCompatibilityTest.class.getResourceAsStream("/counter-economics/" + name)) {
            if (stream == null) {
                throw new IOException("Genuine phase-B old-serializer fixture required: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Phase-B entry point accepts actual application and legacy mappers; it never loads changed serializer classes.
     * The root must call this before compatibility tests, using the pinned archive and a fresh output directory.
     */
    public static void generateBaseline(ObjectMapper applicationJson, ObjectMapper legacyJson,
            Path archive, Path output) throws Exception {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("baseline output must be a fresh directory");
        }
        var manifest = applicationJson.readTree(Files.readString(archive.resolve("manifest.json")));
        for (var pinned : manifest.get("files")) {
            byte[] bytes = Files.readAllBytes(archive.resolve(pinned.get("path").asString()));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
            if (bytes.length != pinned.get("bytes").intValue() || !hash.equals(pinned.get("sha256").asString())) {
                throw new IllegalArgumentException("changed pinned old serializer input");
            }
        }
        Path classes = archive.resolve("backend/shop-core/target/classes");
        try (var loader = new BaselineLoader(classes.toUri().toURL())) {
            Class<?> economicType = loader.loadClass(CheckoutEconomicSnapshot.class.getName());
            Class<?> evidenceType = loader.loadClass(CheckoutEconomicSnapshot.Evidence.class.getName());
            Class<?> planType = loader.loadClass(CounterOrderPlan.class.getName());
            Constructor<?> economicConstructor = economicType.getDeclaredConstructors()[0];
            Constructor<?> evidenceConstructor = evidenceType.getDeclaredConstructors()[0];
            var generated = new LinkedHashMap<String, String>();
            var hashes = new LinkedHashMap<String, String>();
            Object oldUser = null;
            for (String name : List.of("ordinary-v1", "m1-v1", "m2a-user-v1", "m2a-guest-v1")) {
                CustomerParty party = name.contains("guest") ? GUEST : USER;
                boolean manual = !name.startsWith("ordinary");
                var current = oldMoney(null, party, manual);
                var e = current.evidence();
                Object oldEvidence = evidenceConstructor.newInstance(e.tenantNo(), e.scopeRevision(), e.scopeHash(),
                        e.commandHash(), e.catalogVersionNo(), e.catalogRevision(), e.catalogHash());
                Object[] args = {null, oldEvidence, current.buyerNo(), current.entityNo(), current.storeNo(),
                    current.currency(), current.payMode(), current.fulfillment(), current.fulfillmentAuthority(),
                    current.lines(), current.goodsMinor(), current.freightMinor(), current.discountMinor(),
                    current.dueMinor(), current.platformMinor(), current.merchantMinor(), current.activity(),
                    current.coupon()};
                Object old = economicConstructor.newInstance(args);
                String hash;
                if (name.startsWith("m2a")) {
                    hash = counterHash(legacyJson, party, old);
                } else {
                    hash = CheckoutDigests.hash(legacyJson, Arrays.asList(manual ? "CHECKOUT_MANUAL_V1" : "CHECKOUT_V1",
                            "MAIN", 1L, "scope", "CAT1", 1L, "catalog", LINES, null, Map.of(), old));
                    if (manual) {
                        hash = CheckoutDigests.hash(legacyJson, List.of("MANUAL_OFFLINE", hash));
                    }
                }
                args[0] = hash;
                old = economicConstructor.newInstance(args);
                String bytes = applicationJson.writeValueAsString(old);
                if (!bytes.equals(legacyJson.writeValueAsString(old))) {
                    throw new IllegalArgumentException("application/legacy baseline serialization differs");
                }
                generated.put(name + ".json", bytes);
                hashes.put(name + ".json", CheckoutDigests.hash(bytes));
                hashes.put(name + ".quoteHash", hash);
                if (name.equals("m2a-user-v1")) {
                    oldUser = old;
                }
            }
            Object oldPlan = planType.getDeclaredConstructors()[0].newInstance("O1", "SO1", "OP1", "INTENT1",
                    "request", USER, command(), oldUser, plan(oldMoney(null, USER, true)).stockNeeds(),
                    "LOCAL", 1, "manifest", 1L);
            String planBytes = applicationJson.writeValueAsString(oldPlan);
            if (!planBytes.equals(legacyJson.writeValueAsString(oldPlan))) {
                throw new IllegalArgumentException("application/legacy old plan serialization differs");
            }
            generated.put("m2a-pending-plan-v1.json", planBytes);
            hashes.put("m2a-pending-plan-v1.json", CheckoutDigests.hash(planBytes));
            hashes.put("planHash", CheckoutDigests.hash(applicationJson, oldPlan));
            generated.put("hashes.json", applicationJson.writeValueAsString(hashes));
            Files.createDirectories(output);
            for (var item : generated.entrySet()) {
                Files.writeString(output.resolve(item.getKey()), item.getValue());
            }
        }
    }

    /** Phase B compares both actual injected mapper variants against genuine archived old output and hashes. */
    public static void verifyBaseline(ObjectMapper applicationJson, ObjectMapper legacyJson, Path fixtures)
            throws IOException {
        var hashes = applicationJson.readTree(Files.readString(fixtures.resolve("hashes.json")));
        for (ObjectMapper mapper : List.of(applicationJson, legacyJson)) {
            var codec = new CounterEconomicCodec(mapper);
            for (String name : List.of("ordinary-v1", "m1-v1", "m2a-user-v1", "m2a-guest-v1")) {
                String frozen = Files.readString(fixtures.resolve(name + ".json"));
                var value = mapper.readValue(frozen, CheckoutEconomicSnapshot.class);
                assertThat(mapper.writeValueAsString(value)).isEqualTo(frozen);
                assertThat(CheckoutDigests.hash(frozen)).isEqualTo(hashes.get(name + ".json").asString());
                if (name.startsWith("m2a")) {
                    codec.requireQuoteHash(name.contains("guest") ? GUEST : USER, LINES, value, value.quoteHash());
                    assertThat(codec.readQuoteSnapshot(frozen).promotions()).isEmpty();
                }
            }
            String oldPlan = Files.readString(fixtures.resolve("m2a-pending-plan-v1.json"));
            var plan = codec.readPlan(oldPlan);
            assertThat(codec.writePlan(plan)).isEqualTo(oldPlan);
            assertThat(codec.planHash(plan)).isEqualTo(hashes.get("planHash").asString());
        }
    }

    private static final class BaselineLoader extends URLClassLoader {
        private BaselineLoader(URL classes) {
            super(new URL[] {classes}, CounterEconomicCompatibilityTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(CheckoutEconomicSnapshot.class.getName())
                    || name.startsWith(CounterOrderPlan.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
