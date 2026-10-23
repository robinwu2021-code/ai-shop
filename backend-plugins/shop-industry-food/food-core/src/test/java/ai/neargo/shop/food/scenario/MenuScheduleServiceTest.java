package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.entity.FnbMenuSchedule;
import ai.neargo.shop.food.entity.FnbMenuException;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuScheduleServiceTest {
    static ReplaceMenuScheduleReq req(long version) {
        return new ReplaceMenuScheduleReq("SCHEDULE", version,
                List.of(new WeekScheduleInput(1, "22:00", "02:00", 1)),
                List.of(new ExceptionInput("2026-10-19", "CLOSED", null, null, 0)), "2026-10-01", "2026-10-31");
    }
    @Test void replaceReadAndClearWorkingRulesKeepOldPublicationAndAllCounters() {
        new MenuServiceTest().run(m -> {
            var s = m.service(); var sql = m.fixture().sql();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("夜宵"), "create").configNo();
            sql.update("UPDATE fnb_menu SET status='PUBLISHED',published_revision_no='HISTORY'");
            var result = s.replaceSchedule("E", "A", id, "actor", req(0), "schedule");
            assertThat(result.menu().version()).isEqualTo(1); assertThat(result.menu().configVersion()).isEqualTo(1);
            assertThat(result.menu().status()).isEqualTo("DRAFT");
            assertThat(s.getMenu("E", "A", id).schedule()).isEqualTo(result.schedule());
            assertThat(sql.queryForObject("SELECT end_day_offset FROM fnb_menu_schedule", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT published_revision_no FROM fnb_menu", String.class))
                    .isEqualTo("HISTORY");
            var clear = new ReplaceMenuScheduleReq("SCHEDULE", 1L, List.of(), List.of(), null, null);
            var empty = s.replaceSchedule("E", "A", id, "actor", clear, "clear");
            assertThat(empty.schedule().weekSchedules()).isEmpty(); assertThat(empty.schedule().validFrom()).isNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_menu_schedule", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_menu_exception", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT valid_from FROM fnb_menu", LocalDate.class)).isNull();
        });
    }
    @Test void completedReceiptSurvivesLaterEditsAndTimezoneChangesButNewWritesUseCas() {
        new MenuServiceTest().run(m -> {
            var s = m.service(); var sql = m.fixture().sql();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("早餐"), "create").configNo();
            var result = s.replaceSchedule("E", "A", id, "actor", req(0), "schedule");
            sql.update("UPDATE fnb_store_policy SET timezone='Asia/Tokyo'");
            assertThat(s.replaceSchedule("E", "A", id, "actor", req(0), "schedule")).isEqualTo(result);
            MenuServiceTest.code(() -> s.replaceSchedule("E", "A", id, "actor", req(1), "new"), ErrorCode.CONFLICT);
            sql.update("UPDATE fnb_store_policy SET timezone='Asia/Shanghai'");
            MenuServiceTest.code(() -> s.replaceSchedule("E", "A", id, "actor", req(0), "stale"), ErrorCode.CONFLICT);
            s.replaceSchedule("E", "A", id, "actor", req(1), "next");
            assertThat(s.replaceSchedule("E", "A", id, "actor", req(0), "schedule")).isEqualTo(result);
        });
    }
    @Test void outboxFailureRestoresAllOldRowsDatesCountersAndKeyIsRetryable() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("早餐"), "create").configNo();
            var first = s.replaceSchedule("E", "A", id, "actor", req(0), "first");
            var clear = new ReplaceMenuScheduleReq("SCHEDULE", 1L, List.of(), List.of(), null, null);
            m.fail().set(true);
            assertThatThrownBy(() -> s.replaceSchedule("E", "A", id, "actor", clear, "clear"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(s.getMenu("E", "A", id).schedule()).isEqualTo(first.schedule());
            assertThat(s.getMenu("E", "A", id).config().version()).isEqualTo(1);
            m.fail().set(false);
            assertThat(s.replaceSchedule("E", "A", id, "actor", clear, "clear").menu().version()).isEqualTo(2);
        });
    }
    @Test void foreignStoreAndSharedHeadAndInvalidRulesNeverMutateAnything() {
        new MenuServiceTest().run(m -> {
            var s = m.service();
            var id = s.createMenu("E", "A", "actor", MenuServiceTest.input("早餐"), "create").configNo();
            MenuServiceTest.code(() -> s.replaceSchedule("E", "B", id, "actor", req(0), "foreign"),
                    ErrorCode.NOT_FOUND);
            var binding = new FnbMenuStore(); binding.setEntityNo("E"); binding.setMenuNo(id); binding.setStoreNo("B");
            m.template().getMapper(MenuStoreMapper.class).insert(binding);
            MenuServiceTest.code(() -> s.replaceSchedule("E", "A", id, "actor", req(0), "shared"), ErrorCode.CONFLICT);
            assertThat(m.fixture().sql().queryForObject("SELECT COUNT(*) FROM fnb_menu_schedule", Integer.class))
                    .isZero();
        });
    }
    @Test void registeredScopeProtectsAllFourRealMappersWithoutManualPredicates() {
        new MenuServiceTest().run(m -> {
            var menu = m.template().getMapper(MenuMapper.class);
            var stores = m.template().getMapper(MenuStoreMapper.class);
            var weeks = m.template().getMapper(MenuScheduleMapper.class);
            var exceptions = m.template().getMapper(MenuExceptionMapper.class);
            for (String entity : List.of("E", "OTHER")) {
                var head = new FnbMenu(); head.setEntityNo(entity); head.setMenuNo(entity); head.setName("早餐");
                head.setMenuType("TIME"); head.setTimezone("Asia/Shanghai"); head.setPriority(0);
                head.setStatus("DRAFT"); head.setConfigVersion(0L); menu.insert(head);
                var store = new FnbMenuStore(); store.setEntityNo(entity); store.setMenuNo(entity);
                store.setStoreNo("A"); stores.insert(store);
                var week = new FnbMenuSchedule(); week.setEntityNo(entity); week.setMenuNo(entity);
                week.setScheduleNo(entity); week.setWeekday(1); week.setStartTime(LocalTime.of(8, 0));
                week.setEndTime(LocalTime.of(10, 0)); week.setEndDayOffset(0); weeks.insert(week);
                var ex = new FnbMenuException(); ex.setEntityNo(entity); ex.setMenuNo(entity);
                ex.setExceptionNo(entity); ex.setLocalDate(LocalDate.parse("2026-10-19"));
                ex.setMode("CLOSED"); ex.setEndDayOffset(0); exceptions.insert(ex);
            }
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("E")));
            try {
                assertThat(menu.selectList(null)).extracting(FnbMenu::getEntityNo).containsExactly("E");
                assertThat(stores.selectList(null)).extracting(FnbMenuStore::getEntityNo).containsExactly("E");
                assertThat(weeks.selectList(null)).extracting(FnbMenuSchedule::getEntityNo).containsExactly("E");
                assertThat(exceptions.selectList(null)).extracting(FnbMenuException::getEntityNo).containsExactly("E");
            } finally { DataScopeContext.clear(); }
        });
    }
}
