package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuSkuRecipeInput;
import ai.neargo.shop.food.dto.ReplaceMenuCompositionReq;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Store-bound composition is a real editable aggregate, never another product catalogue. */
class MenuCompositionServiceTest {
    static MenuItemInput item(String id, String goods, String mode, List<String> groups) {
        return new MenuItemInput(id, goods, 0, true, "EXPLICIT", List.of("S1"), mode, groups, List.of());
    }
    static ReplaceMenuCompositionReq req(long version, String category, MenuItemInput item) {
        return new ReplaceMenuCompositionReq("COMPOSITION", version,
                List.of(new MenuCategoryInput(category, "饮品", 0, List.of(item))));
    }
    @Test void serverIdsAndSkuWhitelistAndExplicitEmptyOptionsSurviveReadAndClearWithoutChangingTimeRules() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("午餐"), "create").configNo();
            var first = s.replaceComposition("E", "A", id, "actor", req(0, null, item(null, "G1", "REPLACE",
                    List.of())), "composition");
            var category = first.composition().categories().getFirst();
            var dish = category.items().getFirst();
            assertThat(category.categoryNo()).isNotBlank(); assertThat(dish.menuItemNo()).isNotBlank();
            assertThat(dish.optionMode()).isEqualTo("REPLACE"); assertThat(dish.optionGroupNos()).isEmpty();
            assertThat(s.getMenu("E", "A", id).composition()).isEqualTo(first.composition());
            assertThat(s.getMenu("E", "A", id).schedule().weekSchedules()).isEmpty();
            var next = req(1, category.categoryNo(), item(dish.menuItemNo(), "G1", "INHERIT", List.of()));
            assertThat(s.replaceComposition("E", "A", id, "actor", next, "next").menu().version()).isEqualTo(2);
            var cleared = s.replaceComposition("E", "A", id, "actor",
                    new ReplaceMenuCompositionReq("COMPOSITION", 2L, List.of()), "clear");
            assertThat(cleared.composition().categories()).isEmpty();
            assertThat(s.replaceComposition("E", "A", id, "actor", req(0, null,
                    item(null, "G1", "REPLACE", List.of())), "composition")).isEqualTo(first);
        });
    }
    @Test void idsCannotBeBorrowedAcrossMenusOrReboundToAnotherProductAndCasIsAtomic() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var a = s.createMenu("E", "A", "actor", MenuServiceTest.input("午餐"), "a").configNo();
            var b = s.createMenu("E", "A", "actor", MenuServiceTest.input("晚餐"), "b").configNo();
            var c = s.replaceComposition("E", "A", a, "actor", req(0, null,
                    item(null, "G1", "INHERIT", List.of())), "first").composition().categories().getFirst();
            MenuServiceTest.code(() -> s.replaceComposition("E", "A", b, "actor",
                    req(0, c.categoryNo(), c.items().getFirst()), "borrow"), ErrorCode.CONFLICT);
            MenuServiceTest.code(() -> s.replaceComposition("E", "A", a, "actor",
                    req(1, c.categoryNo(), item(c.items().getFirst().menuItemNo(), "G2", "INHERIT", List.of())),
                    "rebind"), ErrorCode.CONFLICT);
            MenuServiceTest.code(() -> s.replaceComposition("E", "A", a, "actor",
                    req(0, c.categoryNo(), c.items().getFirst()), "stale"), ErrorCode.CONFLICT);
        });
    }
    @Test void badSkuAndInvalidModesFailBeforeWritingAndOutboxFailureRollsBackAllRows() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("午餐"), "create").configNo();
            var bad = new MenuItemInput(null, "G1", 0, true, "EXPLICIT", List.of("FOREIGN"), "INHERIT",
                    List.of(), List.of());
            MenuServiceTest.code(() -> s.replaceComposition("E", "A", id, "actor", req(0, null, bad), "bad"),
                    ErrorCode.BAD_REQUEST);
            m.fail().set(true);
            assertThatThrownBy(() -> s.replaceComposition("E", "A", id, "actor",
                    req(0, null, item(null, "G1", "REPLACE", List.of())), "retry"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(s.getMenu("E", "A", id).composition().categories()).isEmpty();
            assertThat(s.getMenu("E", "A", id).config().version()).isZero();
            m.fail().set(false);
            assertThat(s.replaceComposition("E", "A", id, "actor",
                    req(0, null, item(null, "G1", "REPLACE", List.of())), "retry").menu().version()).isEqualTo(1);
        });
    }
    @Test void inheritedScopeAndEmptyExplicitSkuCannotBeConfused() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("午餐"), "create").configNo();
            for (var bad : List.of(new MenuItemInput(null, "G1", 0, true, "EXPLICIT", List.of(), "INHERIT",
                    List.of(), List.of()), new MenuItemInput(null, "G1", 0, true, "ALL", List.of("S1"), "INHERIT",
                    List.of(), List.of()), new MenuItemInput(null, "G1", 0, true, "ALL", List.of(), "INHERIT",
                    List.of("GROUP"), List.of()), new MenuItemInput(null, "G1", 0, true, "ALL", List.of(), "REPLACE",
                    List.of(), List.of(new MenuSkuRecipeInput("S1", "RECIPE"))))) {
                MenuServiceTest.code(() -> s.replaceComposition("E", "A", id, "actor", req(0, null, bad), "bad"),
                        ErrorCode.BAD_REQUEST);
            }
        });
    }
}
