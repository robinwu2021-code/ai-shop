package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort.UserBrief;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.entity.OrdOfflineRecordEvent;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.EventMapper;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.RecordMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;
import ai.neargo.shop.trade.policy.CashStepPolicy;
import ai.neargo.shop.trade.service.OfflineRecordService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 外部记账的唯一实现：币种与步长、实收时间窗口、幂等、锁行比版本、留痕都在这里各判一次。
 *
 * <p>时间口径：{@code received_at}、事件 {@code occurred_at} 存 UTC；{@code created_at} 沿仓库审计填充的 JVM 本地时间，
 * 读出时按本地时区换回时刻。所有「按天」的判断（列表日期、31 天窗口）都按门店市场时区。
 */
@Service
public class OfflineRecordServiceImpl implements OfflineRecordService {
    /** 补记与更正 / 作废的窗口（已拍板 D4、D7）：门店市场时区「今天 − 31 天」00:00 起。 */
    private static final int WINDOW_DAYS = 31;

    /** 与 /biz/payment/orders 同一条：to − from ≤ 93。 */
    private static final int MAX_RANGE_DAYS = 93;

    /** 实收时间允许比服务器快一点（手机时钟偏差），不允许记未来的钱。 */
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(2);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final RecordMapper records;
    private final EventMapper events;
    private final SubOrderMapper subOrders;
    private final MerchantQueryPort merchants;
    private final MarketPort markets;
    private final UserQueryPort users;
    private final TransactionTemplate writes;

    /** 只依赖本域两张表、子单归属查询与市场主数据；不碰支付域任何端口。 */
    public OfflineRecordServiceImpl(
            RecordMapper records,
            EventMapper events,
            SubOrderMapper subOrders,
            MerchantQueryPort merchants,
            MarketPort markets,
            UserQueryPort users,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.records = records;
        this.events = events;
        this.subOrders = subOrders;
        this.merchants = merchants;
        this.markets = markets;
        this.users = users;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public OfflineRecordView record(Scope scope, RecordCmd cmd) {
        MarketBrief market = market(scope);
        if (!market.currency().equals(cmd.currency())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        requireStep(cmd.amountMinor(), market.currency(), market);
        String keyHash = CheckoutDigests.hash(cmd.idempotencyKey());
        String requestHash = requestHash(scope, cmd);
        OrdOfflineRecord prior = records.findKey(scope.entityNo(), scope.storeNo(), keyHash);
        if (prior != null) {
            return replay(prior, requestHash, market);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // 实收时间按秒存：响应按秒回显，App 原样带回更正时不会因毫秒差被当成「改了」
        Instant receivedAt = (cmd.receivedAt() == null ? now : cmd.receivedAt()).truncatedTo(ChronoUnit.SECONDS);
        requireReceivedAt(receivedAt, now, market);
        if (cmd.orderNo() != null) {
            requireOwnOrder(scope, cmd.orderNo());
        }
        OrdOfflineRecord row;
        try {
            row = writes.execute(tx -> insert(scope, cmd, keyHash, requestHash, receivedAt, now));
        } catch (DuplicateKeyException race) {
            // 同一键并发双写：唯一键兜住，输的那一路读回赢家那条再按摘要判同体 / 异体
            OrdOfflineRecord winner = records.findKey(scope.entityNo(), scope.storeNo(), keyHash);
            if (winner == null) {
                throw race;
            }
            return replay(winner, requestHash, market);
        }
        return views(List.of(row), market).getFirst();
    }

    @Override
    public RecordPage list(Scope scope, ListQuery query) {
        MarketBrief market = market(scope);
        ZoneId zone = ZoneId.of(market.timeZone());
        LocalDate[] range = range(query.from(), query.to(), zone);
        LambdaQueryWrapper<OrdOfflineRecord> where = inRange(scope, range, zone);
        if (query.methods() != null && !query.methods().isEmpty()) {
            where.in(OrdOfflineRecord::getMethod, query.methods());
        }
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            where.in(OrdOfflineRecord::getStatus, query.statuses());
        }
        if (query.orderNo() != null) {
            where.eq(OrdOfflineRecord::getOrderNo, query.orderNo());
        }
        if (query.q() != null) {
            String pattern = escapeLike(query.q());
            where.and(w -> w.like(OrdOfflineRecord::getOrderNo, pattern).or().like(OrdOfflineRecord::getNote, pattern));
        }
        if (query.cursor() != null) {
            long[] after = decodeCursor(query.cursor());
            LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(after[0]), ZoneOffset.UTC);
            where.and(w -> w.lt(OrdOfflineRecord::getReceivedAt, at)
                    .or(x -> x.eq(OrdOfflineRecord::getReceivedAt, at).lt(OrdOfflineRecord::getId, after[1])));
        }
        where.orderByDesc(OrdOfflineRecord::getReceivedAt).orderByDesc(OrdOfflineRecord::getId)
                .last("LIMIT " + (query.size() + 1));
        List<OrdOfflineRecord> rows = records.selectList(where);
        String next = null;
        if (rows.size() > query.size()) {
            rows = rows.subList(0, query.size());
            OrdOfflineRecord last = rows.getLast();
            next = encodeCursor(last.getReceivedAt().toInstant(ZoneOffset.UTC).toEpochMilli(), last.getId());
        }
        return new RecordPage(views(rows, market), next);
    }

    @Override
    public Summary summary(Scope scope, LocalDate from, LocalDate to) {
        MarketBrief market = market(scope);
        ZoneId zone = ZoneId.of(market.timeZone());
        LocalDate[] range = range(from, to, zone);
        long count = records.selectCount(
                inRange(scope, range, zone).eq(OrdOfflineRecord::getStatus, OrdOfflineRecord.RECORDED));
        long amount = records.sumRecorded(
                scope.entityNo(), scope.storeNo(), utc(range[0], zone), utc(range[1].plusDays(1), zone));
        return new Summary(range[0].toString(), range[1].toString(), market.currency(), count, amount);
    }

    @Override
    public OfflineRecordView correct(Scope scope, String recordNo, CorrectCmd cmd) {
        MarketBrief market = market(scope);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant newReceivedAt = cmd.receivedAt() == null ? null : cmd.receivedAt().truncatedTo(ChronoUnit.SECONDS);
        if (newReceivedAt != null) {
            requireReceivedAt(newReceivedAt, now, market);
        }
        OrdOfflineRecord row = writes.execute(tx -> {
            OrdOfflineRecord current = lockForChange(scope, recordNo, cmd.expectedVersion(), now, market);
            // 不许借更正换币种；步长按原记录币种判
            if (!current.getCurrency().equals(cmd.currency())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            requireStep(cmd.amountMinor(), current.getCurrency(), market);
            LocalDateTime toAt = newReceivedAt == null
                    ? current.getReceivedAt() : LocalDateTime.ofInstant(newReceivedAt, ZoneOffset.UTC);
            if (current.getAmountMinor() == cmd.amountMinor() && current.getMethod().equals(cmd.method())
                    && current.getReceivedAt().equals(toAt)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            OrdOfflineRecordEvent event = event(recordNo, OrdOfflineRecordEvent.CORRECT, scope.actorNo(), now);
            event.setFromAmountMinor(current.getAmountMinor());
            event.setToAmountMinor(cmd.amountMinor());
            event.setFromMethod(current.getMethod());
            event.setToMethod(cmd.method());
            if (!current.getReceivedAt().equals(toAt)) {
                event.setFromReceivedAt(current.getReceivedAt());
                event.setToReceivedAt(toAt);
            }
            event.setReason(cmd.reason());
            current.setAmountMinor(cmd.amountMinor());
            current.setMethod(cmd.method());
            current.setReceivedAt(toAt);
            current.setCorrected(true);
            return update(current, event);
        });
        return views(List.of(row), market).getFirst();
    }

    @Override
    public OfflineRecordView voidRecord(Scope scope, String recordNo, VoidCmd cmd) {
        MarketBrief market = market(scope);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OrdOfflineRecord row = writes.execute(tx -> {
            OrdOfflineRecord current = lockForChange(scope, recordNo, cmd.expectedVersion(), now, market);
            OrdOfflineRecordEvent event = event(recordNo, OrdOfflineRecordEvent.VOID, scope.actorNo(), now);
            event.setReason(cmd.reason());
            current.setStatus(OrdOfflineRecord.VOIDED);
            return update(current, event);
        });
        return views(List.of(row), market).getFirst();
    }

    // ------------------------------------------------------------------ 写

    private OrdOfflineRecord insert(
            Scope scope, RecordCmd cmd, String keyHash, String requestHash, Instant receivedAt, Instant now) {
        OrdOfflineRecord row = new OrdOfflineRecord();
        row.setTenantNo("MAIN");
        row.setRecordNo(BizKey.next(BizKey.OFFLINE_RECORD));
        row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo());
        row.setAmountMinor(cmd.amountMinor());
        row.setCurrency(cmd.currency());
        row.setMethod(cmd.method());
        row.setOrderNo(cmd.orderNo());
        row.setNote(cmd.note());
        row.setReceivedAt(LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
        row.setStatus(OrdOfflineRecord.RECORDED);
        row.setCorrected(false);
        row.setRecordedBy(scope.actorNo());
        row.setIdemKeyHash(keyHash);
        row.setRequestHash(requestHash);
        row.setVersion(1L);
        // 审计列沿仓库口径（JVM 本地时间）；显式给值，让 recordedAt 与 CREATE 事件是同一刻
        row.setCreatedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
        records.insert(row);
        OrdOfflineRecordEvent event = event(row.getRecordNo(), OrdOfflineRecordEvent.CREATE, scope.actorNo(), now);
        event.setToAmountMinor(cmd.amountMinor());
        event.setToMethod(cmd.method());
        events.insert(event);
        return row;
    }

    /** 乐观锁加一与事件同一事务；行已锁住，0 行只可能是并发改过，按版本不符回 10409。 */
    private OrdOfflineRecord update(OrdOfflineRecord current, OrdOfflineRecordEvent event) {
        if (records.updateById(current) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        events.insert(event);
        return current;
    }

    /** 本店、未作废、版本对得上、记账未超窗口（已拍板 D7-C，越界是状态不允许，回 10409）。 */
    private OrdOfflineRecord lockForChange(
            Scope scope, String recordNo, long expectedVersion, Instant now, MarketBrief market) {
        OrdOfflineRecord row = records.lock(scope.entityNo(), scope.storeNo(), recordNo);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdOfflineRecord.VOIDED.equals(row.getStatus()) || row.getVersion() != expectedVersion) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Instant recordedAt = row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        if (recordedAt.isBefore(windowStart(now, market))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return row;
    }

    private static OrdOfflineRecordEvent event(String recordNo, String action, String actorNo, Instant now) {
        OrdOfflineRecordEvent event = new OrdOfflineRecordEvent();
        event.setTenantNo("MAIN");
        event.setRecordNo(recordNo);
        event.setAction(action);
        event.setActorNo(actorNo);
        event.setOccurredAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        event.setCreatedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
        return event;
    }

    // ------------------------------------------------------------------ 判

    /** 门店所属主体的市场：币种、小数位、时区都从这里取，不写死。 */
    private MarketBrief market(Scope scope) {
        return markets.find(merchants.marketOf(scope.entityNo()))
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
    }

    private static void requireStep(long amountMinor, String currency, MarketBrief market) {
        if (!CashStepPolicy.onStep(amountMinor, currency, market.currencyScale())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void requireReceivedAt(Instant receivedAt, Instant now, MarketBrief market) {
        if (receivedAt.isAfter(now.plus(FUTURE_SKEW)) || receivedAt.isBefore(windowStart(now, market))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static Instant windowStart(Instant now, MarketBrief market) {
        ZoneId zone = ZoneId.of(market.timeZone());
        return LocalDate.ofInstant(now, zone).minusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant();
    }

    /** 订单视角的 orderNo 就是子单号；只校验归属本店，不读也不改它的状态（已拍板 D3）。 */
    private void requireOwnOrder(Scope scope, String orderNo) {
        Long hit = DataScopeContext.executeWithoutScope(() -> subOrders.selectCount(
                Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, orderNo)
                        .eq(OrdSubOrder::getEntityNo, scope.entityNo())
                        .eq(OrdSubOrder::getStoreNo, scope.storeNo())));
        if (hit == null || hit == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private OfflineRecordView replay(OrdOfflineRecord row, String requestHash, MarketBrief market) {
        if (!row.getRequestHash().equals(requestHash)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return views(List.of(row), market).getFirst();
    }

    /** 首次请求的摘要：同一键换了操作人或任一字段都算不同体。实收时间按请求原样（不传就是不传）。 */
    private static String requestHash(Scope scope, RecordCmd cmd) {
        return CheckoutDigests.hash(String.join("\u0001",
                scope.actorNo(),
                Long.toString(cmd.amountMinor()),
                cmd.currency(),
                cmd.method(),
                Objects.toString(cmd.orderNo(), ""),
                Objects.toString(cmd.note(), ""),
                cmd.receivedAt() == null ? "" : Long.toString(cmd.receivedAt().toEpochMilli())));
    }

    private static LocalDate[] range(LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;
        if (end.isBefore(start) || start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return new LocalDate[] {start, end};
    }

    private static LambdaQueryWrapper<OrdOfflineRecord> inRange(Scope scope, LocalDate[] range, ZoneId zone) {
        return Wrappers.<OrdOfflineRecord>lambdaQuery()
                .eq(OrdOfflineRecord::getTenantNo, "MAIN")
                .eq(OrdOfflineRecord::getEntityNo, scope.entityNo())
                .eq(OrdOfflineRecord::getStoreNo, scope.storeNo())
                .ge(OrdOfflineRecord::getReceivedAt, utc(range[0], zone))
                .lt(OrdOfflineRecord::getReceivedAt, utc(range[1].plusDays(1), zone));
    }

    private static LocalDateTime utc(LocalDate day, ZoneId zone) {
        return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
    }

    /** 关键字按字面匹配：% 与 _ 不当通配。 */
    private static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encodeCursor(long epochMillis, long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((epochMillis + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static long[] decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
            if (parts.length != 2) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (IllegalArgumentException malformed) {
            // 游标是本服务发出的不透明串；解不开就是调用方拼错了，不是服务端故障
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    // ------------------------------------------------------------------ 读

    private List<OfflineRecordView> views(List<OrdOfflineRecord> rows, MarketBrief market) {
        if (rows.isEmpty()) {
            return List.of();
        }
        ZoneId zone = ZoneId.of(market.timeZone());
        Map<String, List<OrdOfflineRecordEvent>> trail = events.selectList(
                        Wrappers.<OrdOfflineRecordEvent>lambdaQuery()
                                .eq(OrdOfflineRecordEvent::getTenantNo, "MAIN")
                                .in(OrdOfflineRecordEvent::getRecordNo,
                                        rows.stream().map(OrdOfflineRecord::getRecordNo).toList())
                                .orderByAsc(OrdOfflineRecordEvent::getId))
                .stream()
                .collect(Collectors.groupingBy(OrdOfflineRecordEvent::getRecordNo));
        Map<String, String> names = new HashMap<>();
        Function<String, String> nameOf = no -> names.computeIfAbsent(no,
                k -> users.find(k).map(UserBrief::nickname).orElse(null));
        List<OfflineRecordView> out = new ArrayList<>(rows.size());
        for (OrdOfflineRecord row : rows) {
            List<HistoryView> history = trail.getOrDefault(row.getRecordNo(), List.of()).stream()
                    .map(e -> new HistoryView(
                            iso(e.getOccurredAt(), zone),
                            e.getActorNo(),
                            nameOf.apply(e.getActorNo()),
                            e.getAction(),
                            e.getReason(),
                            e.getFromAmountMinor(),
                            e.getToAmountMinor(),
                            e.getFromMethod(),
                            e.getToMethod(),
                            iso(e.getFromReceivedAt(), zone),
                            iso(e.getToReceivedAt(), zone)))
                    .toList();
            out.add(new OfflineRecordView(
                    row.getRecordNo(),
                    row.getAmountMinor(),
                    row.getCurrency(),
                    row.getMethod(),
                    row.getOrderNo(),
                    row.getNote(),
                    iso(row.getReceivedAt(), zone),
                    row.getRecordedBy(),
                    nameOf.apply(row.getRecordedBy()),
                    row.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(zone)
                            .toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).format(ISO),
                    row.getStatus(),
                    Boolean.TRUE.equals(row.getCorrected()),
                    row.getVersion(),
                    history));
        }
        return out;
    }

    /** UTC 存储 → 门店市场偏移的 ISO 8601。 */
    private static String iso(LocalDateTime utc, ZoneId zone) {
        return utc == null ? null
                : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toOffsetDateTime()
                        .truncatedTo(ChronoUnit.SECONDS).format(ISO);
    }
}
