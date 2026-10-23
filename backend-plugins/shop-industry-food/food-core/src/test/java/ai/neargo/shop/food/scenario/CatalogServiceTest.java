package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSkuRecipeInput;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import java.math.BigDecimal;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.CatalogServiceImpl;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Reads original persisted bindings and frozen publications; dynamic offers never become frozen availability. */
class CatalogServiceTest extends FoodWriteGuardFixture {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    record Context(Fixture f, CatalogServiceImpl service, Map<String, MenuPublicationSnapshot> snapshots,
            AtomicReference<List<DishOfferQueryPort.Offer>> offers,
            AtomicReference<SalesRestrictionEvaluationVO> limits) { }
    private MenuPublicationSnapshot snapshot(String menu, String source, int priority, String group) {
        var item = new MenuItemInput("I" + menu, "G", 0, true, "EXPLICIT", List.of("S"),
                "REPLACE", List.of(), List.of());
        var config = new MenuConfigResultVO(new MenuVO(menu, "A", menu, "TIME", "UTC", priority, group,
                "DRAFT", 0L, 0L), new MenuScheduleVO(null, null,
                List.of(new WeekScheduleInput(3, "00:00", "23:59", 0)), List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("C" + menu, "Category", 0, List.of(item)))),
                List.of(new MenuContextInput(source, "DINE_IN", null, null)));
        return new MenuPublicationSnapshot(4, "MENU", menu, 0,
                new MenuPublicationSnapshot.Captured("E", "A", config,
                        List.of(new DishVO("G", "SINGLE", 5,
                                new DishPresentation("Frozen display", List.of(), List.of(), List.of(), List.of()),
                                new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0)),
                        List.of(new MenuPublicationSnapshot.ItemOptions(item.menuItemNo(), List.of())),
                                List.of(), List.of()),
                List.of(new SkuIdentityFact("S", "G", "E", "A", 0)),
                List.of(new GoodsFact("G", "E", "A", "Original", "cover", true, "APPROVED", 0,
                        List.of(new SkuFact("S", "Small", 0)))), null);
    }
    private MenuPublicationSnapshot modifierSnapshot(String kind) {
        var json = new ObjectMapper(); var base = snapshot("M1", "POS", 0, null);
        var addon = new SkuIdentityFact("ADD", "ADDG", "E", "A", 0);
        var modifier = new PublicationSnapshot(1, "MODIFIER", "MOD", 0,
                new ModifierConfigVO("MOD", kind, 0, "DRAFT", new ModifierConfigVO.Data("Addon", "ADD", null,
                        "COMBO".equals(kind)
                                ? List.of(new ModifierConfigVO.Component("COMPONENT", "MEMBER", new BigDecimal("2")))
                                : List.of())), null, List.of(), List.of(addon), List.of(), null);
        String mod = PublicationDocuments.freeze(json, modifier); String hash = PublicationDocuments.hash(json, mod);
        var group = new PublicationSnapshot(1, "OPTION_GROUP", "GROUP", 0, null,
                new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 0, "DRAFT", new OptionGroupConfigVO.Data("Options",
                        BigDecimal.ZERO, BigDecimal.ONE, null, Map.of(),
                        List.of(new OptionGroupConfigVO.Item("OPTION", "MODIFIER", null, "MOD", BigDecimal.ZERO,
                                BigDecimal.ONE, BigDecimal.ZERO, 0)))),
                List.of(new PublicationSnapshot.Dependency("MODREV", "MODIFIER", "MOD", 1, hash)), List.of(),
                        List.of(), null);
        String grp = PublicationDocuments.freeze(json, group); var old = base.menu().definition();
        var item = new MenuItemInput("IM1", "G", 0, true, "EXPLICIT", List.of("S"), "INHERIT", List.of(), List.of());
        var definition = new MenuConfigResultVO(old.config(), old.schedule(),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CM1", "Category", 0, List.of(item)))),
                        old.contexts());
        var captured = new MenuPublicationSnapshot.Captured("E", "A", definition, base.menu().dishes(),
                List.of(new MenuPublicationSnapshot.ItemOptions("IM1", List.of("GROUP"))),
                List.of(new MenuPublicationSnapshot.Dependency("MODREV", "MODIFIER", "MOD", 1, 1, hash, mod),
                        new MenuPublicationSnapshot.Dependency("GROUPREV", "OPTION_GROUP", "GROUP", 1, 1,
                                PublicationDocuments.hash(json, grp), grp)), List.of());
        return new MenuPublicationSnapshot(4, "MENU", "M1", 0, captured,
                List.of(base.skuFacts().getFirst(), addon), base.goodsFacts(), null);
    }
    private void run(Consumer<Context> test) {
        runner().run(c -> {
            var f = prepare(c);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                    + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_menu (" + common + "menu_no VARCHAR(32),name VARCHAR(64),"
                    + "menu_type VARCHAR(16),timezone VARCHAR(64),valid_from DATE,valid_to DATE,priority INT,"
                    + "exclusive_group VARCHAR(32),status VARCHAR(16),config_version BIGINT,published_revision" +
                            "_no VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_menu_store (" + common + "menu_no VARCHAR(32),store_no VARCHAR(32),"
                    + "override_revision_no VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_service_calendar (" + common +
                    "calendar_no VARCHAR(32),store_no VARCHAR(32),"
                    + "local_date DATE,mode VARCHAR(16),intervals VARCHAR(255),policy_no VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_guest_session (" + common + "session_no VARCHAR(32),check_no VARCHAR(32),"
                    + "buyer_no VARCHAR(32),device_no VARCHAR(32),token_hash BINARY(32),expires_at DATETIME(3),"
                    + "scope VARCHAR(255),store_no VARCHAR(32),table_no VARCHAR(32),revoked_at DATETIME(3))");
            for (String menu : List.of("M1", "M2")) {
                f.sql().update("INSERT INTO fnb_menu(entity_no,menu_no,status,version) VALUES('E',?,'DRAFT',1)", menu);
                f.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no) VALUES('E',?,'A')", menu);
            }
            var template = c.getBean(SqlSessionTemplate.class);
            for (var type : List.of(MenuMapper.class, MenuStoreMapper.class, GuestSessionMapper.class,
                    ServiceCalendarMapper.class)) template.getConfiguration().addMapper(type);
            var snapshots = new HashMap<String, MenuPublicationSnapshot>();
            snapshots.put("M1", snapshot("M1", "POS", 0, null)); snapshots.put("M2", snapshot("M2", "POS", 0, null));
            var json = c.getBean(ObjectMapper.class); var revisions = mock(ConfigRevisionService.class);
            when(revisions.resolveRevision(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenAnswer(a -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        var body = snapshots.get(a.getArgument(3, String.class));
                        String doc = MenuPublicationDocuments.freeze(json, body);
                        return new ConfigRevisionContentVO(new ConfigRevisionVO("R" + body.configNo(), "MENU",
                                body.configNo(), 1, NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
                                PublicationDocuments.hash(json, doc), "EFFECTIVE"), doc);
                    });
            var operation = mock(StoreOperationService.class);
            when(operation.getPolicy(anyString(), anyString(), any())).thenReturn(
                    new StoreOperationPolicyResultVO("P", "UTC", 0, "PREPAY", List.of(), 0));
            var stores = mock(StoreDirectoryPort.class);
            when(stores.cards(any())).thenReturn(Map.of("A", new StoreDirectoryPort.StoreCard("A", "Store", "E",
                    null, "ACTIVE", true, "00:00-23:59", null, null, null, 0, 0)));
            var original = mock(GoodsCatalogQueryPort.class);
            when(original.getGoods(anyString(), anyString(), anyString())).thenAnswer(a -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return snapshots.get("M1").goodsFacts().getFirst();
            });
            var offers = new AtomicReference<>(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", 100L,
                    "CNY", "KNOWN", 2, "KNOWN", true)));
            var offerPort = mock(DishOfferQueryPort.class);
            when(offerPort.getOffers(anyString(), anyString(), any())).thenAnswer(a -> {
                List<String> requested = a.getArgument(2);
                return offers.get().stream().filter(o -> requested.contains(o.skuNo())).toList();
            });
            var restrictions = mock(SalesRestrictionService.class);
            var limits = new AtomicReference<>(new SalesRestrictionEvaluationVO(false, null, false, NOW, List.of()));
            when(restrictions.evaluate(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(a -> limits.get());
            var service = new CatalogServiceImpl(template.getMapper(MenuMapper.class),
                    template.getMapper(MenuStoreMapper.class),
                    template.getMapper(ServiceCalendarMapper.class), template.getMapper(GuestSessionMapper.class),
                    revisions, operation, restrictions, stores, original, offerPort, json,
                    c.getBean(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
            test.accept(new Context(f, service, snapshots, offers, limits));
        });
    }
    @Test void readsPublishedSnapshotDespiteDraftHeadAndPreservesEachMenuItemIdentity() {
        run(c -> {
            var result = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null));
            assertThat(result.menus()).hasSize(2); assertThat(result.items()).hasSize(2);
            assertThat(result.items()).extracting(i -> i.menuItemNo()).containsExactly("IM1", "IM2");
            assertThat(result.items().getFirst().skus().getFirst().transactable()).isTrue();
            assertThat(result.items().getFirst().dish().presentation().displayName()).isEqualTo("Frozen display");
            c.f.sql().update("UPDATE fnb_menu SET status='DISABLED' WHERE menu_no='M1'");
            assertThat(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null)).items()).hasSize(1);
        });
    }
    @Test void unknownStockPriceAndOffsaleNeverBecomeZeroPriceOrAvailable() {
        run(c -> {
            c.offers.set(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", null, null,
                    "UNKNOWN", null, "UNKNOWN", true)));
            var sku = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN",
                    null)).items().getFirst().skus().getFirst();
            assertThat(sku.transactable()).isFalse(); assertThat(sku.priceMinor()).isNull();
            assertThat(sku.available()).isNull();
            c.offers.set(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", 0L, "CNY",
                    "KNOWN", 1, "KNOWN", true)));
            var free = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null))
                    .items().getFirst().skus().getFirst();
            assertThat(free.transactable()).isTrue(); assertThat(free.priceMinor()).isZero();
            c.offers.set(List.of());
            assertThat(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null))
                    .items().getFirst().skus().getFirst().transactable()).isFalse();
        });
    }
    @Test void originalPhysicalStockCannotOverrideSelloutOrLessThanOneCountQuota() {
        run(c -> {
            c.limits.set(new SalesRestrictionEvaluationVO(true, null, false, NOW, List.of()));
            assertThat(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null))
                    .items().getFirst().skus().getFirst().transactable()).isFalse();
            c.limits.set(new SalesRestrictionEvaluationVO(false, new BigDecimal("0.5"), false, NOW, List.of()));
            var sku = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null))
                    .items().getFirst().skus().getFirst();
            assertThat(sku.remainingQuota()).isEqualByComparingTo("0.5");
            assertThat(sku.transactable()).isFalse();
        });
    }
    @Test void sourceFulfillmentCalendarAndExclusivePriorityActuallyFilterPublishedMenus() {
        run(c -> {
            assertThat(c.service.read("E", "A", new CatalogQuery("WAITER", "DINE_IN", null)).menus()).isEmpty();
            c.snapshots.put("M1", snapshot("M1", "POS", 1, "MEAL"));
            c.snapshots.put("M2", snapshot("M2", "POS", 2, "MEAL"));
            assertThat(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null)).menus())
                    .extracting(m -> m.menuNo()).containsExactly("M2");
            c.snapshots.put("M1", snapshot("M1", "POS", 2, "MEAL"));
            assertThatThrownBy(() -> c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null)))
                    .isInstanceOf(RuntimeException.class);
            c.f.sql().update("INSERT INTO fnb_service_calendar(entity_no,calendar_no,store_no,local_date,mode," +
                    "intervals) "
                    + "VALUES('E','CAL','A','2026-10-21','CLOSED','[]')");
            assertThat(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null)).menus()).isEmpty();
        });
    }
    @Test void inheritReadsFrozenGroupAndModifierDependenciesWithoutCurrentWorkingOptionLookups() {
        run(c -> {
            var json = new ObjectMapper(); var base = c.snapshots.get("M1");
            var modifier = new PublicationSnapshot(1, "MODIFIER", "MOD", 0,
                    new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                            new ModifierConfigVO.Data("Frozen modifier", null, null,
                                    List.of(new ModifierConfigVO.Component("MC", "MAT", new BigDecimal("2"))))),
                    null, List.of(), List.of(), List.of(), null);
            String mod = PublicationDocuments.freeze(json, modifier); String modHash =
                    PublicationDocuments.hash(json, mod);
            var group = new PublicationSnapshot(1, "OPTION_GROUP", "GROUP", 0, null,
                    new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 0, "DRAFT",
                            new OptionGroupConfigVO.Data("Frozen group", BigDecimal.ZERO, BigDecimal.ONE,
                                    null, Map.of(), List.of())),
                    List.of(new PublicationSnapshot.Dependency("MODREV", "MODIFIER", "MOD", 1, modHash)),
                    List.of(), List.of(), null);
            String grp = PublicationDocuments.freeze(json, group);
            String materialHash = MaterialCatalogFacts.definitionHash("MAT", "E", "Secret ingredient", "EA", 0, "OWN");
            var material = new MaterialFact("MAT", "E", "Secret ingredient", "EA", 0, "OWN", "BASE_ONLY",
                    "INTEGER_ONLY", materialHash);
            var recipe = new RecipeVO("REC", "A", "S", "RECIPE", BigDecimal.ONE, "EA", "DRAFT", 0, 0,
                    List.of(new RecipeLineVO("RL", "MAT", BigDecimal.ONE, "EA", new BigDecimal("0.1"), materialHash)),
                    List.of());
            var recipeBody = new RecipePublicationSnapshot(3, "RECIPE", "REC", 0, recipe, base.skuFacts(),
                    List.of(material), List.of(), null);
            String recipeDoc = PublicationDocuments.freeze(json, recipeBody);
            var old = base.menu().definition();
            var item = new MenuItemInput("IM1", "G", 0, true, "EXPLICIT", List.of("S"),
                    "INHERIT", List.of(), List.of(new MenuSkuRecipeInput("S", "REC")));
            var def = new MenuConfigResultVO(old.config(), old.schedule(),
                    new MenuCompositionVO(List.of(new MenuCategoryInput("CM1", "Category", 0, List.of(item)))),
                    List.of(new MenuContextInput("H5", "PICKUP", null, null)));
            var capture = new MenuPublicationSnapshot.Captured("E", "A", def, base.menu().dishes(),
                    List.of(new MenuPublicationSnapshot.ItemOptions("IM1", List.of("GROUP"))),
                    List.of(new MenuPublicationSnapshot.Dependency("MODREV", "MODIFIER", "MOD", 1, 1, modHash, mod),
                            new MenuPublicationSnapshot.Dependency("GROUPREV", "OPTION_GROUP", "GROUP", 1, 1,
                                    PublicationDocuments.hash(json, grp), grp),
                            new MenuPublicationSnapshot.Dependency("RECREV", "RECIPE", "REC", 1, 3,
                                    PublicationDocuments.hash(json, recipeDoc), recipeDoc)), List.of());
            c.snapshots.put("M1", new MenuPublicationSnapshot(4, "MENU", "M1", 0, capture,
                    base.skuFacts(), base.goodsFacts(), null));
            var result = c.service.publicCatalog("A", "PICKUP");
            String publicJson = json.writeValueAsString(result);
            assertThat(publicJson).doesNotContain("\"itemRef\"", "\"materialDefinitionHash\"", "\"lossRate\"",
                    "\"adjustments\"", "\"components\"", "\"quantity\"");
            assertThat(result.items().getFirst().optionGroupNos()).containsExactly("GROUP");
            assertThat(result.items().getFirst().skus().getFirst().recipeNo()).isEqualTo("REC");
            assertThat(result.menus().getFirst().dependencies()).hasSize(3);
            assertThat(result.menus().getFirst().dependencies()).extracting(d -> d.revisionNo())
                    .containsExactly("MODREV", "GROUPREV", "RECREV");
            assertThat(result.menus().getFirst().dependencies().get(2).recipe().recipeNo()).isEqualTo("REC");
            assertThat(result.items().getFirst().skus().getFirst().transactable()).isFalse();
            assertThat(result.items().getFirst().skus().getFirst().reason()).isEqualTo("RECIPE_STOCK_UNVERIFIED");
            assertThat(json.readTree(publicJson).path("menus").get(0).path("dependencies").get(0)
                    .path("modifier").path("name").asString()).isEqualTo("Frozen modifier");
            assertThat(result.menus().getFirst().dependencies().get(1).optionGroup().data().name())
                    .isEqualTo("Frozen group");
        });
    }
    @Test void dictionaryChoicesKeepFrozenPublicLabelsAndVersionsWithoutOwnerColumns() {
        run(c -> {
            var json = new ObjectMapper(); var base = c.snapshots.get("M1");
            var value = new SpecValueFact("VALUE", "DIM", "Frozen less sugar", "Sugar", "ENUM", "PROP",
                    "MERCHANT", "E", "PLATFORM", null, 2, 3);
            var group = new PublicationSnapshot(1, "OPTION_GROUP", "GROUP", 0, null,
                    new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 0, "DRAFT", new OptionGroupConfigVO.Data("Options",
                            BigDecimal.ZERO, BigDecimal.ONE, null, Map.of(),
                            List.of(new OptionGroupConfigVO.Item("OPTION", "SPEC_VALUE", "VALUE", null,
                                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 0)))),
                    List.of(), List.of(), List.of(value), null);
            String doc = PublicationDocuments.freeze(json, group); var old = base.menu().definition();
            var item = new MenuItemInput("IM1", "G", 0, true, "EXPLICIT", List.of("S"), "INHERIT", List.of(),
                    List.of());
            var definition = new MenuConfigResultVO(old.config(), old.schedule(),
                    new MenuCompositionVO(List.of(new MenuCategoryInput("CM1", "Category", 0,
                            List.of(item)))), old.contexts());
            var captured = new MenuPublicationSnapshot.Captured("E", "A", definition, base.menu().dishes(),
                    List.of(new MenuPublicationSnapshot.ItemOptions("IM1", List.of("GROUP"))),
                    List.of(new MenuPublicationSnapshot.Dependency("GROUPREV", "OPTION_GROUP", "GROUP", 1, 1,
                            PublicationDocuments.hash(json, doc), doc)), List.of());
            c.snapshots.put("M1", new MenuPublicationSnapshot(4, "MENU", "M1", 0, captured,
                    base.skuFacts(), base.goodsFacts(), null));
            String text = json.writeValueAsString(c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null)));
            var labels = json.readTree(text).path("menus").get(0).path("dependencies").get(0).path("specValues");
            assertThat(labels.isArray()).isTrue(); assertThat(labels.size()).isEqualTo(1);
            assertThat(labels.get(0).path("valueNo").asString()).isEqualTo("VALUE");
            assertThat(labels.get(0).path("label").asString()).isEqualTo("Frozen less sugar");
            assertThat(labels.get(0).path("valueVersion").asLong()).isEqualTo(2);
            assertThat(labels.get(0).path("dimVersion").asLong()).isEqualTo(3);
            assertThat(text).doesNotContain("valueEntityNo", "dimEntityNo");
        });
    }
    @Test void addonSalesSkuUsesLiveOriginalPriceStockAndRestrictionWithoutInventingZero() {
        run(c -> {
            c.snapshots.put("M1", modifierSnapshot("ADDON"));
            for (long price : List.of(500L, 700L)) {
                c.offers.set(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", 100L, "CNY", "KNOWN",
                        2, "KNOWN", true),
                        new DishOfferQueryPort.Offer("ADD", "ADDG", "E", "A", price, "CNY", "KNOWN", 4,
                                "KNOWN", true)));
                var result = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null));
                var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
                var offer = tree.path("menus").get(0).path("dependencies").get(0).path("modifier").path("salesOffer");
                assertThat(offer.path("skuNo").asString()).isEqualTo("ADD");
                assertThat(offer.path("priceMinor").asLong()).isEqualTo(price);
                assertThat(offer.path("available").asInt()).isEqualTo(4);
                assertThat(offer.path("transactable").asBoolean()).isTrue();
                assertThat(result.menus().getFirst().dependencies().get(1).optionGroup().data().items().getFirst()
                        .optionItemNo()).isEqualTo("OPTION");
            }
            c.offers.set(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", 100L, "CNY", "KNOWN", 2,
                    "KNOWN", true)));
            var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                    c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null))));
            var missing = tree.path("menus").get(0).path("dependencies").get(0).path("modifier").path("salesOffer");
            assertThat(missing.path("priceMinor").isNull()).isTrue();
            assertThat(missing.path("transactable").asBoolean()).isFalse();
        });
    }
    @Test void comboSalesPriceDoesNotClaimComponentStockOrExposeItsStructure() {
        run(c -> {
            c.snapshots.put("M1", modifierSnapshot("COMBO"));
            c.offers.set(List.of(new DishOfferQueryPort.Offer("S", "G", "E", "A", 100L, "CNY", "KNOWN", 2,
                    "KNOWN", true),
                    new DishOfferQueryPort.Offer("ADD", "ADDG", "E", "A", 500L, "CNY", "KNOWN", 4, "KNOWN", true)));
            String text = new ObjectMapper().writeValueAsString(c.service.read("E", "A", new
                    CatalogQuery("POS", "DINE_IN", null)));
            var offer = new ObjectMapper().readTree(text).path("menus").get(0).path("dependencies").get(0)
                    .path("modifier").path("salesOffer");
            assertThat(offer.path("priceMinor").asLong()).isEqualTo(500);
            assertThat(offer.path("transactable").asBoolean()).isFalse();
            assertThat(offer.path("requiresSelectionQuote").asBoolean()).isTrue();
            assertThat(offer.path("reason").asString()).isEqualTo("COMPONENT_QUOTE_REQUIRED");
            assertThat(text).doesNotContain("\"components\"", "\"quantity\"", "MEMBER");
        });
    }
    @Test void bundleKeepsPublicChoiceConstraintsAndBasePriceButRequiresActualComponentQuote() {
        run(c -> {
            var base = c.snapshots.get("M1"); var json = new ObjectMapper();
            var addon = new SkuIdentityFact("ADD", "ADDG", "E", "A", 0);
            var bundle = new BundleConfigVO("S", 0, "DRAFT", new BundleConfigVO.Data(0, null,
                    List.of(new BundleGroupInput("BG", "Choice", BigDecimal.ONE, BigDecimal.ONE, null, 0)),
                    List.of(new BundleChoiceInput("BC", "BG", "ADD", BigDecimal.ONE, "INCLUDED", BigDecimal.ONE,
                            BigDecimal.ZERO, BigDecimal.ONE, 0))));
            String doc = PublicationDocuments.freeze(json, new BundlePublicationSnapshot(2, "BUNDLE", "S", 0,
                    bundle, List.of(base.skuFacts().getFirst(), addon), null));
            var dish = base.menu().dishes().getFirst();
            var captured = new MenuPublicationSnapshot.Captured("E", "A", base.menu().definition(),
                    List.of(new DishVO("G", "BUNDLE", 5, dish.presentation(), dish.measurement(), 0, 0)),
                    base.menu().itemOptions(), List.of(new MenuPublicationSnapshot.Dependency("BUNREV", "BUNDLE", "S",
                            1, 2, PublicationDocuments.hash(json, doc), doc)), List.of());
            c.snapshots.put("M1", new MenuPublicationSnapshot(4, "MENU", "M1", 0, captured,
                    List.of(base.skuFacts().getFirst(), addon), base.goodsFacts(), null));
            var result = c.service.read("E", "A", new CatalogQuery("POS", "DINE_IN", null));
            var sku = result.items().getFirst().skus().getFirst();
            assertThat(sku.priceMinor()).isEqualTo(100); assertThat(sku.transactable()).isFalse();
            assertThat(sku.reason()).isEqualTo("BUNDLE_QUOTE_REQUIRED");
            assertThat(result.menus().getFirst().dependencies().getFirst().bundle().data().choices().getFirst().skuNo())
                    .isEqualTo("ADD");
        });
    }
    @Test void guestRequiresOriginalHashedCredentialBuyerScopeAndStoreAndForcesQrDineIn() {
        run(c -> {
            c.snapshots.put("M1", snapshot("M1", "QR", 0, null));
            c.snapshots.put("M2", snapshot("M2", "QR", 0, null));
            byte[] hash;
            try { hash = MessageDigest.getInstance("SHA-256").digest("secret".getBytes(StandardCharsets.UTF_8)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            c.f.sql().update("INSERT INTO fnb_guest_session(entity_no,session_no,store_no,token_hash,expires_a" +
                    "t,scope,buyer_no) "
                    + "VALUES('E','SESSION','A',?,'2026-10-21 13:00:00','[\"TABLE_MENU\"]','BUYER')", hash);
            assertThat(c.service.guest("A", "SESSION", "secret", "BUYER").items()).hasSize(2);
            assertThatThrownBy(() -> c.service.guest("A", "SESSION", "secret",
                    "OTHER")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> c.service.guest("B", "SESSION", "secret",
                    "BUYER")).isInstanceOf(RuntimeException.class);
            c.f.sql().update("UPDATE fnb_guest_session SET revoked_at='2026-10-21 11:00:00'");
            assertThatThrownBy(() -> c.service.guest("A", "SESSION", "secret",
                    "BUYER")).isInstanceOf(RuntimeException.class);
        });
    }
}
