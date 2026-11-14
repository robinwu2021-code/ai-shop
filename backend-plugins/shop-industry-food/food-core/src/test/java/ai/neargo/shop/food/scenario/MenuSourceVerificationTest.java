package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

class MenuSourceVerificationTest {
    static final OffsetDateTime NOW = ConfigRevisionServiceTest.NOW;

    static Query query(String rev, Kind kind, String ref, String offer, Mode mode) {
        return new Query("E", "A", "M", rev, kind, ref, offer, null, mode);
    }

    static String document(ObjectMapper json, String skuScope) {
        var item =
                new MenuItemInput(
                        "ITEM",
                        "G",
                        0,
                        true,
                        skuScope,
                        "ALL".equals(skuScope) ? List.of() : List.of("S1"),
                        "REPLACE",
                        List.of(),
                        List.of());
        var other =
                new MenuItemInput(
                        "OTHER",
                        "G",
                        1,
                        true,
                        "EXPLICIT",
                        List.of("S2"),
                        "REPLACE",
                        List.of(),
                        List.of());
        var config =
                new MenuConfigResultVO(
                        new MenuVO("M", "A", "Menu", "TIME", "UTC", 0, null, "DRAFT", 0L, 0L),
                        new MenuScheduleVO(null, null, List.of(), List.of()),
                        new MenuCompositionVO(
                                List.of(
                                        new MenuCategoryInput("CAT", "Category", 0, List.of(item)),
                                        new MenuCategoryInput("CAT2", "Other", 1, List.of(other)))),
                        List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var captured =
                new Captured(
                        "E",
                        "A",
                        config,
                        List.of(
                                new DishVO(
                                        "G",
                                        "SINGLE",
                                        0,
                                        new DishPresentation(
                                                null, List.of(), List.of(), List.of(), List.of()),
                                        new MeasurementPolicy("EA", "COUNT", 0, null),
                                        0,
                                        0)),
                        List.of(
                                new ItemOptions("ITEM", List.of()),
                                new ItemOptions("OTHER", List.of())),
                        List.of(),
                        List.of());
        return PublicationDocuments.freeze(
                json,
                new MenuPublicationSnapshot(
                        4,
                        "MENU",
                        "M",
                        0,
                        captured,
                        List.of(
                                new SkuIdentityFact("S1", "G", "E", "A", 0),
                                new SkuIdentityFact("S2", "G", "E", "A", 0)),
                        List.of(
                                new GoodsFact(
                                        "G",
                                        "E",
                                        "A",
                                        "Goods",
                                        null,
                                        true,
                                        "APPROVED",
                                        0,
                                        List.of(
                                                new SkuFact("S1", "One", 0),
                                                new SkuFact("S2", "Two", 0)))),
                        null));
    }

    static void insert(
            JdbcTemplate sql,
            ObjectMapper json,
            String revision,
            long sequence,
            OffsetDateTime at,
            String document) {
        sql.update(
                "INSERT INTO fnb_config_revision"
                    + " (entity_no,revision_no,config_kind,config_no,revision,"
                    + "content_schema,definition_snapshot,content_hash,effective_at,status,scope_store_nos)"
                    + " VALUES ('E',?,'MENU','M',?,4,?,?,?,'EFFECTIVE','[\"A\"]')",
                revision,
                sequence,
                document,
                PublicationDocuments.hash(json, document),
                at.toLocalDateTime());
    }

    @Test
    void verifiesOriginalScopeAndExplicitSalesOfferMembership() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var s = c.getBean(ConfigRevisionService.class);
                            insert(
                                    f.sql(),
                                    json,
                                    "R1",
                                    1,
                                    NOW.minusDays(1),
                                    document(json, "EXPLICIT"));
                            var proof =
                                    s.verifyMenuResource(
                                            query("R1", Kind.SKU, "S1", "ITEM", Mode.CURRENT));
                            assertThat(proof.resource().salesOfferRef()).isEqualTo("ITEM");
                            assertThat(proof.publication().publication().revisionNo())
                                    .isEqualTo("R1");
                            assertThat(proof.verifiedAt()).isEqualTo(NOW);
                            assertThat(proof.validUntil()).isEqualTo(NOW.plusSeconds(30));
                            assertThat(proof.receiptNo()).hasSize(64);
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.SKU,
                                                            "S2",
                                                            "ITEM",
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    new Query(
                                                            "OTHER",
                                                            "A",
                                                            "M",
                                                            "R1",
                                                            Kind.MENU,
                                                            "M",
                                                            null,
                                                            null,
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    new Query(
                                                            "E",
                                                            "B",
                                                            "M",
                                                            "R1",
                                                            Kind.MENU,
                                                            "M",
                                                            null,
                                                            null,
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                        });
    }

    @Test
    void allSkuScopeUsesFrozenGoodsInsteadOfOnlyLookingAtExplicitSkuList() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var s = c.getBean(ConfigRevisionService.class);
                            insert(f.sql(), json, "R1", 1, NOW.minusDays(1), document(json, "ALL"));
                            assertThat(
                                            s.verifyMenuResource(
                                                            query(
                                                                    "R1",
                                                                    Kind.SKU,
                                                                    "S2",
                                                                    "ITEM",
                                                                    Mode.CURRENT))
                                                    .resource()
                                                    .resourceRef())
                                    .isEqualTo("S2");
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.OPTION_GROUP,
                                                            "INHERITED",
                                                            "ITEM",
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                        });
    }

    @Test
    void currentRejectsOldAndFutureRevisionsWhileHistoryPreservesOriginalFacts() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var s = c.getBean(ConfigRevisionService.class);
                            String doc = document(json, "EXPLICIT");
                            insert(f.sql(), json, "R1", 1, NOW.minusDays(2), doc);
                            insert(f.sql(), json, "R2", 2, NOW.minusDays(1), doc);
                            insert(f.sql(), json, "R3", 3, NOW.plusSeconds(5), doc);
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.MENU,
                                                            "M",
                                                            null,
                                                            Mode.CURRENT)),
                                    ErrorCode.CONFLICT);
                            var history =
                                    s.verifyMenuResource(
                                            query("R1", Kind.MENU, "M", null, Mode.HISTORICAL));
                            assertThat(history.query().mode()).isEqualTo(Mode.HISTORICAL);
                            assertThat(history.publication().publication().revisionNo())
                                    .isEqualTo("R1");
                            assertThat(
                                            s.verifyMenuResource(
                                                            query(
                                                                    "R2",
                                                                    Kind.MENU,
                                                                    "M",
                                                                    null,
                                                                    Mode.CURRENT))
                                                    .validUntil())
                                    .isEqualTo(NOW.plusSeconds(5));
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R3",
                                                            Kind.MENU,
                                                            "M",
                                                            null,
                                                            Mode.HISTORICAL)),
                                    ErrorCode.CONFLICT);
                            f.sql()
                                    .update(
                                            "UPDATE fnb_config_revision SET status='CANCELLED'"
                                                    + " WHERE revision_no='R1'");
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.MENU,
                                                            "M",
                                                            null,
                                                            Mode.HISTORICAL)),
                                    ErrorCode.CONFLICT);
                        });
    }

    @Test
    void originalHashTamperingAndMissingOfferConditionsCannotBecomeProofs() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var s = c.getBean(ConfigRevisionService.class);
                            insert(
                                    f.sql(),
                                    json,
                                    "R1",
                                    1,
                                    NOW.minusDays(1),
                                    document(json, "EXPLICIT"));
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.SKU,
                                                            "S1",
                                                            null,
                                                            Mode.CURRENT)),
                                    ErrorCode.BAD_REQUEST);
                            code(
                                    () ->
                                            s.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.OFFER,
                                                            "ITEM",
                                                            "OTHER",
                                                            Mode.CURRENT)),
                                    ErrorCode.BAD_REQUEST);
                            f.sql()
                                    .update(
                                            "UPDATE fnb_config_revision SET content_hash=?",
                                            "0".repeat(64));
                            assertThatThrownBy(
                                            () ->
                                                    s.verifyMenuResource(
                                                            query(
                                                                    "R1",
                                                                    Kind.MENU,
                                                                    "M",
                                                                    null,
                                                                    Mode.CURRENT)))
                                    .isInstanceOf(IllegalStateException.class);
                        });
    }

    @Test
    void optionIdentityRequiresOriginalGroupAndOfferEvenWhenGroupsShareTheSameSpecValue() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var service = c.getBean(ConfigRevisionService.class);
                            var options = c.getBean(OptionGroupService.class);
                            var deps = new ArrayList<Dependency>();
                            var groups = new ArrayList<String>();
                            for (int i = 0; i < 2; i++) {
                                String group =
                                        options.createOptionGroup(
                                                        "E",
                                                        "A",
                                                        "actor",
                                                        new CreateOptionGroupReq(
                                                                "Group" + i,
                                                                BigDecimal.ZERO,
                                                                BigDecimal.ONE),
                                                        "group" + i)
                                                .configNo();
                                options.replaceOptionGroupItems(
                                        "E",
                                        "A",
                                        group,
                                        "actor",
                                        new ReplaceOptionGroupItemsReq(
                                                0L,
                                                List.of(
                                                        new OptionItemInput(
                                                                "SAME" + i,
                                                                "SPEC_VALUE",
                                                                "V".repeat(64),
                                                                null,
                                                                BigDecimal.ZERO,
                                                                BigDecimal.ONE,
                                                                BigDecimal.ZERO,
                                                                0)),
                                                List.of(),
                                                new SelectionRules(List.of(), List.of())),
                                        "items" + i);
                                var meta =
                                        service.publishOptionGroup(
                                                        "E",
                                                        "A",
                                                        group,
                                                        "actor",
                                                        new PublishConfigReq(1L, NOW, null),
                                                        "publish" + i)
                                                .publication();
                                var content = service.getRevision("E", "A", meta.revisionNo());
                                deps.add(
                                        new Dependency(
                                                meta.revisionNo(),
                                                "OPTION_GROUP",
                                                group,
                                                meta.revision(),
                                                1,
                                                meta.contentHash(),
                                                content.definitionSnapshot()));
                                groups.add(group);
                            }
                            var body =
                                    json.readValue(
                                            document(json, "EXPLICIT"),
                                            MenuPublicationSnapshot.class);
                            var old = body.menu();
                            var definition = old.definition();
                            var cats =
                                    definition.composition().categories().stream()
                                            .map(
                                                    cat ->
                                                            new MenuCategoryInput(
                                                                    cat.categoryNo(),
                                                                    cat.name(),
                                                                    cat.sortNo(),
                                                                    cat.items().stream()
                                                                            .map(
                                                                                    item ->
                                                                                            new MenuItemInput(
                                                                                                    item
                                                                                                            .menuItemNo(),
                                                                                                    item
                                                                                                            .goodsNo(),
                                                                                                    item
                                                                                                            .sortNo(),
                                                                                                    item
                                                                                                            .enabled(),
                                                                                                    item
                                                                                                            .skuScope(),
                                                                                                    item
                                                                                                            .skuNos(),
                                                                                                    "REPLACE",
                                                                                                    List
                                                                                                            .of(
                                                                                                                    groups
                                                                                                                            .get(
                                                                                                                                    "ITEM"
                                                                                                                                                    .equals(
                                                                                                                                                            item
                                                                                                                                                                    .menuItemNo())
                                                                                                                                            ? 0
                                                                                                                                            : 1)),
                                                                                                    List
                                                                                                            .of()))
                                                                            .toList()))
                                            .toList();
                            var captured =
                                    new Captured(
                                            "E",
                                            "A",
                                            new MenuConfigResultVO(
                                                    definition.config(),
                                                    definition.schedule(),
                                                    new MenuCompositionVO(cats),
                                                    definition.contexts()),
                                            old.dishes(),
                                            List.of(
                                                    new ItemOptions("ITEM", List.of(groups.get(0))),
                                                    new ItemOptions(
                                                            "OTHER", List.of(groups.get(1)))),
                                            deps,
                                            List.of());
                            String doc =
                                    PublicationDocuments.freeze(
                                            json,
                                            new MenuPublicationSnapshot(
                                                    4,
                                                    "MENU",
                                                    "M",
                                                    0,
                                                    captured,
                                                    body.skuFacts(),
                                                    body.goodsFacts(),
                                                    null));
                            insert(f.sql(), json, "R1", 1, NOW, doc);
                            var q =
                                    new Query(
                                            "E",
                                            "A",
                                            "M",
                                            "R1",
                                            Kind.OPTION,
                                            "SAME0",
                                            "ITEM",
                                            groups.get(0),
                                            Mode.CURRENT);
                            assertThat(service.verifyMenuResource(q).resource().optionGroupRef())
                                    .isEqualTo(groups.get(0));
                            code(
                                    () ->
                                            service.verifyMenuResource(
                                                    new Query(
                                                            "E",
                                                            "A",
                                                            "M",
                                                            "R1",
                                                            Kind.OPTION,
                                                            "SAME0",
                                                            "ITEM",
                                                            groups.get(1),
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                            code(
                                    () ->
                                            service.verifyMenuResource(
                                                    new Query(
                                                            "E",
                                                            "A",
                                                            "M",
                                                            "R1",
                                                            Kind.OPTION,
                                                            "V".repeat(32),
                                                            "ITEM",
                                                            groups.get(0),
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                            f.sql()
                                    .update(
                                            "UPDATE fnb_option_group_item SET"
                                                    + " option_item_no='CHANGED' WHERE"
                                                    + " option_item_no='SAME0'");
                            assertThat(service.verifyMenuResource(q).resource().resourceRef())
                                    .isEqualTo("SAME0");
                        });
    }

    @Test
    void unknownPersistedPublicationStatusCannotGrantCurrentOrHistoricalEvidence() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var service = c.getBean(ConfigRevisionService.class);
                            insert(
                                    f.sql(),
                                    json,
                                    "R1",
                                    1,
                                    NOW.minusDays(1),
                                    document(json, "EXPLICIT"));
                            for (String invalid : List.of("DRAFT", "UNKNOWN")) {
                                f.sql()
                                        .update(
                                                "UPDATE fnb_config_revision SET status=? WHERE"
                                                        + " revision_no='R1'",
                                                invalid);
                                for (var mode : Mode.values()) {
                                    code(
                                            () ->
                                                    service.verifyMenuResource(
                                                            query(
                                                                    "R1", Kind.MENU, "M", null,
                                                                    mode)),
                                            ErrorCode.CONFLICT);
                                }
                            }
                        });
    }

    @Test
    void allScopeDoesNotAdmitDependencySkuOutsideTheFrozenGoodsCatalog() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var service = c.getBean(ConfigRevisionService.class);
                            var body =
                                    json.readValue(
                                            document(json, "ALL"), MenuPublicationSnapshot.class);
                            var extra = new SkuIdentityFact("S3", "G", "E", "A", 0);
                            String dependency =
                                    PublicationDocuments.freeze(
                                            json,
                                            new PublicationSnapshot(
                                                    1,
                                                    "MODIFIER",
                                                    "MOD",
                                                    0,
                                                    new ModifierConfigVO(
                                                            "MOD",
                                                            "ADDON",
                                                            0,
                                                            "DRAFT",
                                                            new ModifierConfigVO.Data(
                                                                    "Addon", "S3", null,
                                                                    List.of())),
                                                    null,
                                                    List.of(),
                                                    List.of(extra),
                                                    List.of(),
                                                    null));
                            var old = body.menu();
                            var captured =
                                    new Captured(
                                            "E",
                                            "A",
                                            old.definition(),
                                            old.dishes(),
                                            old.itemOptions(),
                                            List.of(
                                                    new Dependency(
                                                            "DM",
                                                            "MODIFIER",
                                                            "MOD",
                                                            1,
                                                            1,
                                                            PublicationDocuments.hash(
                                                                    json, dependency),
                                                            dependency)),
                                            List.of());
                            var identities = new ArrayList<>(body.skuFacts());
                            identities.add(extra);
                            String doc =
                                    PublicationDocuments.freeze(
                                            json,
                                            new MenuPublicationSnapshot(
                                                    4,
                                                    "MENU",
                                                    "M",
                                                    0,
                                                    captured,
                                                    identities,
                                                    body.goodsFacts(),
                                                    null));
                            insert(f.sql(), json, "R1", 1, NOW.minusDays(1), doc);
                            code(
                                    () ->
                                            service.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.SKU,
                                                            "S3",
                                                            "ITEM",
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                        });
    }

    @Test
    void categoryAndOfferProofsUseFrozenResourceIdentities() {
        new ConfigRevisionServiceTest()
                .run(
                        (c, f) -> {
                            var json = c.getBean(ObjectMapper.class);
                            var service = c.getBean(ConfigRevisionService.class);
                            insert(
                                    f.sql(),
                                    json,
                                    "R1",
                                    1,
                                    NOW.minusDays(1),
                                    document(json, "EXPLICIT"));
                            assertThat(
                                            service.verifyMenuResource(
                                                            query(
                                                                    "R1",
                                                                    Kind.CATEGORY,
                                                                    "CAT",
                                                                    null,
                                                                    Mode.CURRENT))
                                                    .resource()
                                                    .resourceRef())
                                    .isEqualTo("CAT");
                            assertThat(
                                            service.verifyMenuResource(
                                                            query(
                                                                    "R1",
                                                                    Kind.OFFER,
                                                                    "OTHER",
                                                                    "OTHER",
                                                                    Mode.CURRENT))
                                                    .resource()
                                                    .salesOfferRef())
                                    .isEqualTo("OTHER");
                            code(
                                    () ->
                                            service.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.CATEGORY,
                                                            "MISSING",
                                                            null,
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                            code(
                                    () ->
                                            service.verifyMenuResource(
                                                    query(
                                                            "R1",
                                                            Kind.OFFER,
                                                            "MISSING",
                                                            "MISSING",
                                                            Mode.CURRENT)),
                                    ErrorCode.NOT_FOUND);
                        });
    }

    static void code(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }
}
