package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.ExceptionDay;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Policy;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Store;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.user.StoreCardFacts;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Original store reads stay outside transactions; coherent owner snapshots detect intervening
 * changes.
 */
public final class StoreCalendarSourceServiceImpl implements StoreCalendarSourceService {
    private final StorePolicyMapper policies;
    private final ServiceCalendarMapper calendars;
    private final Supplier<StoreCardQueryPort> stores;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate read;

    public StoreCalendarSourceServiceImpl(
            StorePolicyMapper policies,
            ServiceCalendarMapper calendars,
            Supplier<StoreCardQueryPort> stores,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.policies = policies;
        this.calendars = calendars;
        this.stores = stores;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Facts capture(Query q) {
        StoreCalendarSourcePolicy.validateQuery(q);
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        var start = clock.instant();
        var store = store(q);
        var before = load(q);
        var refreshed = store(q);
        var after = load(q);
        require(store.equals(refreshed) && before.equals(after));
        var facts =
                StoreCalendarSourcePolicy.seal(
                        new Facts(
                                1,
                                "STORE_CALENDAR",
                                q,
                                store,
                                before.policies(),
                                before.exceptions(),
                                clock.instant(),
                                start.plusSeconds(30),
                                null));
        try {
            StoreCalendarSourcePolicy.validate(q, facts, clock.instant());
        } catch (IllegalStateException invalid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return facts;
    }

    private Store store(Query q) {
        var port = stores.get();
        if (port == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var cards = port.cards(List.of(q.storeNo()));
        StoreCardFacts.response(cards, List.of(q.storeNo()));
        var card = cards.get(q.storeNo());
        if (card == null || !q.entityNo().equals(card.entityNo()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        return new Store(card.entityNo(), card.storeNo(), card.status(), card.openHours());
    }

    private State load(Query q) {
        return DataScopeContext.executeWithoutScope(
                () ->
                        read.execute(
                                tx -> {
                                    var lower =
                                            LocalDateTime.ofInstant(
                                                    StoreCalendarSourcePolicy.lower(q),
                                                    ZoneOffset.UTC);
                                    var upper =
                                            LocalDateTime.ofInstant(
                                                    StoreCalendarSourcePolicy.upper(q),
                                                    ZoneOffset.UTC);
                                    // 不保留策略历史（TDD §2.5）：当前策略覆盖整个查询范围，生效时间记为范围起点。
                                    var current = policies.current(q.entityNo(), q.storeNo());
                                    if (current == null) throw BizException.of(ErrorCode.NOT_FOUND);
                                    var policyFacts =
                                            List.of(
                                                    new Policy(
                                                            current.getPolicyNo(),
                                                            current.getTimezone(),
                                                            current.getCutoverMinute(),
                                                            lower.toInstant(ZoneOffset.UTC),
                                                            current.getVersion()));
                                    var days =
                                            calendars.selectList(
                                                    Wrappers.<FnbServiceCalendar>lambdaQuery()
                                                            .eq(
                                                                    FnbServiceCalendar::getTenantNo,
                                                                    "MAIN")
                                                            .eq(
                                                                    FnbServiceCalendar::getEntityNo,
                                                                    q.entityNo())
                                                            .eq(
                                                                    FnbServiceCalendar::getStoreNo,
                                                                    q.storeNo())
                                                            .ge(
                                                                    FnbServiceCalendar
                                                                            ::getLocalDate,
                                                                    q.fromDate())
                                                            .le(
                                                                    FnbServiceCalendar
                                                                            ::getLocalDate,
                                                                    q.toDate())
                                                            .orderByAsc(
                                                                    FnbServiceCalendar
                                                                            ::getLocalDate)
                                                            .last("LIMIT 32"));
                                    require(days.size() <= 31);
                                    var exceptions =
                                            days.stream()
                                                    .map(
                                                            d ->
                                                                    new ExceptionDay(
                                                                            d.getCalendarNo(),
                                                                            d.getLocalDate(),
                                                                            d.getMode(),
                                                                            json.readValue(
                                                                                    d
                                                                                            .getIntervals(),
                                                                                    new TypeReference<
                                                                                            List<
                                                                                                    String>>() {}),
                                                                            d.getPolicyNo(),
                                                                            d.getVersion()))
                                                    .toList();
                                    return new State(policyFacts, exceptions);
                                }));
    }

    private static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.CONFLICT);
    }

    private record State(List<Policy> policies, List<ExceptionDay> exceptions) {}
}
