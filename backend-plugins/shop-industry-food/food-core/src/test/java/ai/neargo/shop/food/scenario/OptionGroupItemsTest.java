package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.service.OptionGroupService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;

class OptionGroupItemsTest extends FoodWriteGuardFixture {
    private Fixture fixture(AssertableApplicationContext c) {
        var f = new OptionGroupServiceTest().fixture(c);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,entity_no VARCHAR(32),tenant_no VARCHAR(32),"
                + "created_at DATETIME,created_by VARCHAR(32),updated_at DATETIME,updated_by VARCHAR(32),"
                + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_dish(" + common + "goods_no VARCHAR(32),dish_kind VARCHAR(16),"
                + "prep_seconds INT,status VARCHAR(16),presentation TEXT,"
                + "measurement_policy TEXT,config_version BIGINT)");
        f.sql().execute("CREATE TABLE fnb_dish_option_group(" + common
                + "goods_no VARCHAR(32),group_no VARCHAR(32),sort_no INT,UNIQUE(goods_no,group_no))");
        f.sql().execute("CREATE TABLE fnb_modifier(" + common + "modifier_no VARCHAR(32),name VARCHAR(64),"
                + "kind VARCHAR(16),sales_sku_no VARCHAR(32),published_revision_no VARCHAR(32),status VARCHAR(16))");
        f.sql().update("INSERT INTO fnb_dish(entity_no,goods_no) VALUES('E','G'),('E','X')");
        f.sql().update("INSERT INTO fnb_modifier(entity_no,modifier_no,name,kind,status) "
                + "VALUES('E','M','珍珠','ADDON','DRAFT'),('OTHER','FOREIGN','外主体','ADDON','ACTIVE')");
        return f;
    }
    private String create(OptionGroupService s) {
        return s.createOptionGroup("E", "A", "actor",
                new CreateOptionGroupReq("糖度", BigDecimal.ZERO, new BigDecimal("2")), "new")
                .configNo();
    }
    private ReplaceOptionGroupItemsReq req(long version, List<OptionItemInput> items, List<String> goods) {
        return new ReplaceOptionGroupItemsReq(version, items, goods, new SelectionRules(List.of(), List.of()));
    }
    @Test void saves64CharacterIdentityBindingsAndStableReplayWithCas() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String group = create(s);
            var request = req(0, List.of(SelectionRuleTest.item("A", "V".repeat(64), "0", "1", "0")), List.of("G"));
            var result = s.replaceOptionGroupItems("E", "A", group, "actor", request, "items");
            assertThat(result.version()).isEqualTo(1);
            assertThat(s.replaceOptionGroupItems("E", "A", group, "actor", request, "items")).isEqualTo(result);
            assertThat(s.getOptionGroup("E", "A", group).config().data().items().getFirst().specValueNo())
                    .isEqualTo("V".repeat(64));
            assertThat(f.sql().queryForObject("SELECT goods_no FROM fnb_dish_option_group", String.class)).isEqualTo("G");
            assertThatThrownBy(() -> s.replaceOptionGroupItems(
                    "E", "A", group, "actor", request, "stale"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(1, List.of(), List.of()), "items"))
                    .isInstanceOf(BizException.class);
            for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(2);
            }
        });
    }
    @Test void clearsOnlyKnownCurrentStoreBindingsAndPreservesForeignOrUnknownOnes() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String group = create(s);
            for (String goods : List.of("G", "X", "UNKNOWN")) {
                f.sql().update("INSERT INTO fnb_dish_option_group(entity_no,goods_no,group_no,sort_no) "
                        + "VALUES('E',?,?,0)",
                        goods, group);
            }
            s.replaceOptionGroupItems("E", "A", group, "actor", req(0, List.of(), List.of()), "clear");
            assertThat(f.sql().queryForList("SELECT goods_no FROM fnb_dish_option_group ORDER BY goods_no", String.class))
                    .containsExactly("UNKNOWN", "X");
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(1, List.of(), List.of("X")), "foreign")).isInstanceOf(BizException.class);
        });
    }
    @Test void acceptsOwnedModifierDraftButRejectsForeignOrDisabledReferencesAndChangedItemIdentity() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String group = create(s);
            var addon = new OptionItemInput("A", "MODIFIER", null, "M", BigDecimal.ZERO,
                    BigDecimal.ONE, BigDecimal.ZERO, 0);
            s.replaceOptionGroupItems("E", "A", group, "actor", req(0, List.of(addon), List.of()), "addon");
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(1, List.of(SelectionRuleTest.item("A", "V", "0", "1", "0")), List.of()), "identity"))
                    .isInstanceOf(BizException.class);
            f.sql().update("UPDATE fnb_modifier SET status='DISABLED' WHERE modifier_no='M'");
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(1, List.of(addon), List.of()), "disabled")).isInstanceOf(BizException.class);
            var foreign = new OptionItemInput("B", "MODIFIER", null, "FOREIGN", BigDecimal.ZERO,
                    BigDecimal.ONE, BigDecimal.ZERO, 0);
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(1, List.of(foreign), List.of()), "foreign")).isInstanceOf(BizException.class);
        });
    }
    @Test void rejectsUnavailableNonEnumOrForeignSpecificationFactsWithoutWrites() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String group = create(s);
            for (String ref : List.of("MISSING", "QUANT", "FOREIGN")) {
                assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                        req(0, List.of(SelectionRuleTest.item("A", ref, "0", "1", "0")), List.of()), ref))
                        .isInstanceOf(BizException.class);
            }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_option_group_item", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isOne();
        });
    }
    @Test void refusesAnItemNumberAlreadyOwnedByAnotherGroupWithoutChangingEitherHead() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String first = create(s);
            var request = req(0, List.of(SelectionRuleTest.item("A", "V", "0", "1", "0")), List.of());
            s.replaceOptionGroupItems("E", "A", first, "actor", request, "first-items");
            String second = s.createOptionGroup("E", "A", "actor",
                    new CreateOptionGroupReq("第二组", BigDecimal.ZERO, BigDecimal.ONE), "second").configNo();
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", second, "actor", request, "collision"))
                    .isInstanceOf(BizException.class);
            assertThat(s.getOptionGroup("E", "A", first).config().data().items()).hasSize(1);
            assertThat(s.getOptionGroup("E", "A", second).config().data().items()).isEmpty();
            assertThat(s.getOptionGroup("E", "A", second).config().revision()).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isEqualTo(3);
        });
    }

    @Test void rollsBackTheCompleteDefinitionWhenOutboxFails() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class).run(c -> {
            var f = fixture(c); var s = c.getBean(OptionGroupService.class); String group = create(s);
            c.getBean(OptionGroupServiceTest.Failure.class).enabled.set(true);
            assertThatThrownBy(() -> s.replaceOptionGroupItems("E", "A", group, "actor",
                    req(0, List.of(SelectionRuleTest.item("A", "V", "0", "1", "0")), List.of("G")), "rollback"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(s.getOptionGroup("E", "A", group).config().revision()).isZero();
            for (String table : List.of("fnb_option_group_item", "fnb_dish_option_group")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
            }
        });
    }
}
