package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.ReplaceMenuContextsReq;
import ai.neargo.shop.food.dto.DisableMenuReq;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuContextServiceTest {
    @Test void explicitLocalSourcesSurviveReadAndAreIsolatedFromCompositionAndSchedule() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("早餐"), "create").configNo();
            var input = new ReplaceMenuContextsReq("CONTEXT", 0L,
                    List.of(new MenuContextInput("POS", "DINE_IN", null, null),
                            new MenuContextInput("H5", "DELIVERY", null, null)));
            var first = s.replaceContexts("E", "A", id, "actor", input, "context");
            assertThat(first.contexts()).hasSize(2);
            assertThat(s.getMenu("E", "A", id).contexts()).isEqualTo(first.contexts());
            assertThat(s.getMenu("E", "A", id).composition().categories()).isEmpty();
            assertThat(s.replaceContexts("E", "A", id, "actor", input, "context")).isEqualTo(first);
            MenuServiceTest.code(() -> s.replaceContexts("E", "A", id, "actor", input, "stale"),
                    ErrorCode.CONFLICT);
            MenuServiceTest.code(() -> s.replaceContexts("E", "B", id, "actor", input, "foreign"),
                    ErrorCode.NOT_FOUND);
        });
    }
    @Test void channelAndPricePolicyCannotBeForgedAndDisablePreservesDefinitionsAndReplay() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("早餐"), "create").configNo();
            for (var input : List.of(new MenuContextInput("CHANNEL", "DELIVERY", "FOREIGN", "PRICE"),
                    new MenuContextInput("POS", "DINE_IN", null, "UNVERIFIED"),
                    new MenuContextInput("POS", "DINE_IN", "FOREIGN", null))) {
                MenuServiceTest.code(() -> s.replaceContexts("E", "A", id, "actor",
                        new ReplaceMenuContextsReq("CONTEXT", 0L, List.of(input)), "invalid"), ErrorCode.BAD_REQUEST);
            }
            var req = new DisableMenuReq(0L, "门店停售");
            var stopped = s.disableMenu("E", "A", id, "actor", req, "stop");
            assertThat(stopped.menu().status()).isEqualTo("DISABLED");
            assertThat(m.fixture().sql().queryForObject(
                    "SELECT reason FROM fnb_action_log WHERE action='DISABLE_MENU'", String.class))
                    .isEqualTo("门店停售");
            assertThat(s.disableMenu("E", "A", id, "actor", req, "stop")).isEqualTo(stopped);
            MenuServiceTest.code(() -> s.updateMenu("E", "A", id, "actor",
                    MenuServiceTest.update(1, "午餐", null), "revive"), ErrorCode.CONFLICT);
        });
    }
}
