package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.policy.MenuExclusivePolicy.Member;
import ai.neargo.shop.food.policy.MenuExclusivePolicy.Window;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuExclusivePolicyTest {
    static Instant at(String hour) { return Instant.parse("2026-11-12T" + hour + ":00Z"); }
    static Window w(String from, String to) { return new Window(at(from), at(to)); }

    @Test void pointInTimeKeepsOnlyHighestPriorityPerGroupAndAllUngroupedMenus() {
        record Menu(String no, String group, Integer priority) { }
        var lunch = new Menu("M1", "LUNCH", 10);
        var allDay = new Menu("M2", "LUNCH", 5);
        var drinks = new Menu("M3", null, 1);
        assertThat(MenuExclusivePolicy.winners(List.of(allDay, drinks, lunch), Menu::group, Menu::priority))
                .containsExactlyInAnyOrder(lunch, drinks);
        assertThatThrownBy(() -> MenuExclusivePolicy.winners(
                List.of(lunch, new Menu("M4", "LUNCH", 10)), Menu::group, Menu::priority))
                .isInstanceOf(BizException.class);
    }

    @Test void lowerPriorityMenuLosesExactlyTheOverlapWithHigherPriorityMenusOfTheSameGroup() {
        var result = MenuExclusivePolicy.effective(List.of(
                new Member<>("ALL_DAY", "LUNCH", 5, List.of(w("08:00", "22:00"))),
                new Member<>("SPECIAL", "LUNCH", 10, List.of(w("11:00", "14:00"))),
                new Member<>("OTHER", null, 99, List.of(w("11:00", "14:00"))),
                new Member<>("DINNER", "DINNER", 1, List.of(w("17:00", "21:00")))));
        assertThat(result.get("SPECIAL")).containsExactly(w("11:00", "14:00"));
        assertThat(result.get("ALL_DAY")).containsExactly(w("08:00", "11:00"), w("14:00", "22:00"));
        assertThat(result.get("OTHER")).containsExactly(w("11:00", "14:00"));
        assertThat(result.get("DINNER")).containsExactly(w("17:00", "21:00"));
    }

    @Test void fullyShadowedMenuIsClosedAndEqualPriorityOverlapIsAConflict() {
        var shadowed = MenuExclusivePolicy.effective(List.of(
                new Member<>("A", "G", 1, List.of(w("11:00", "12:00"))),
                new Member<>("B", "G", 2, List.of(w("10:00", "13:00")))));
        assertThat(shadowed.get("A")).isEmpty();
        var disjoint = MenuExclusivePolicy.effective(List.of(
                new Member<>("A", "G", 1, List.of(w("09:00", "10:00"))),
                new Member<>("B", "G", 1, List.of(w("10:00", "11:00")))));
        assertThat(disjoint.get("A")).containsExactly(w("09:00", "10:00"));
        assertThatThrownBy(() -> MenuExclusivePolicy.effective(List.of(
                new Member<>("A", "G", 1, List.of(w("09:00", "10:30"))),
                new Member<>("B", "G", 1, List.of(w("10:00", "11:00"))))))
                .isInstanceOf(BizException.class);
    }
}
