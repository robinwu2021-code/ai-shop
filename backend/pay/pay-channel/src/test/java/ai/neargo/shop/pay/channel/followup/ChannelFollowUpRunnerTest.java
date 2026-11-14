package ai.neargo.shop.pay.channel.followup;

import ai.neargo.shop.spi.pay.PayInstruction;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 补查一轮的判断（TDD-支付服务-I2渠道统一封装 S5）：结论、退避、转人工、多实例领取 */
class ChannelFollowUpRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    /** 内存版：只模拟 open_key 唯一与乐观锁 */
    static final class Store implements FollowUpStore {
        final Map<Long, FollowUpTask> rows = new LinkedHashMap<>();
        final Map<Long, String> status = new LinkedHashMap<>();
        final Map<Long, String> summary = new LinkedHashMap<>();
        long seq;

        @Override
        public boolean open(FollowUpTask t) {
            if (rows.values().stream().anyMatch(r -> "PENDING".equals(status.get(r.id()))
                    && MybatisFollowUpStore.openKey(r).equals(MybatisFollowUpStore.openKey(t)))) {
                return false;
            }
            long id = ++seq;
            rows.put(id, new FollowUpTask(id, 0, t.invocationNo(), t.traceId(), t.operation(), t.payChannel(),
                    t.market(), t.accessMode(), t.outNo(), t.currency(), 0, t.nextAt()));
            status.put(id, "PENDING");
            return true;
        }

        @Override
        public List<FollowUpTask> due(Instant now, int limit) {
            return rows.values().stream().filter(r -> "PENDING".equals(status.get(r.id())) && !r.nextAt().isAfter(now))
                    .limit(limit).toList();
        }

        @Override
        public boolean claim(FollowUpTask t, Instant lease) {
            FollowUpTask cur = rows.get(t.id());
            if (cur.version() != t.version() || !"PENDING".equals(status.get(t.id()))) {
                return false;
            }
            rows.put(t.id(), with(cur, cur.version() + 1, cur.attempts(), lease));
            return true;
        }

        @Override
        public void resolve(FollowUpTask t, String s) {
            close(t, "RESOLVED", s);
        }

        @Override
        public void escalate(FollowUpTask t, String s) {
            close(t, "ESCALATED", s);
        }

        @Override
        public void retry(FollowUpTask t, Instant next, String s) {
            rows.put(t.id(), with(t, t.version() + 1, t.attempts() + 1, next));
            summary.put(t.id(), s);
        }

        private void close(FollowUpTask t, String st, String s) {
            rows.put(t.id(), with(t, t.version() + 1, t.attempts() + 1, t.nextAt()));
            status.put(t.id(), st);
            summary.put(t.id(), s);
        }

        private static FollowUpTask with(FollowUpTask t, long v, int attempts, Instant next) {
            return new FollowUpTask(t.id(), v, t.invocationNo(), t.traceId(), t.operation(), t.payChannel(), t.market(),
                    t.accessMode(), t.outNo(), t.currency(), attempts, next);
        }
    }

    private final Store store = new Store();
    private final List<String> booked = new ArrayList<>();
    private final List<String> queriedTraces = new ArrayList<>();
    private ChannelResult answer;

    private ChannelFollowUpRunner service(Instant now) {
        return new ChannelFollowUpRunner(store, (account, cmd) -> {
            queriedTraces.add(cmd.meta().traceId() + ":" + ((QueryCommand) cmd).outTradeNo());
            return answer;
        }, new ChannelContextFactory(new ChannelCapabilityRegistry(List.of()), new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of();
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }
        }), (ch, out, trade, amount, currency) -> booked.add(out + "/" + trade + "/" + amount + currency),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void open(String outNo) {
        store.open(new FollowUpTask(null, 0, "inv-" + outNo, "trace-" + outNo, "PAY", "TEST", null,
                AccessMode.PLATFORM_SUB, outNo, "CNY", 0, NOW));
    }

    @Test
    @DisplayName("★★★ 查到已付 → 交业务入账、结案；查单沿用原追踪号")
    void paidIsBookedWithOriginalTrace() {
        open("A");
        answer = new ChannelResult.Succeeded("TX-A", new Money(1234, "CNY"), null, Map.of());
        assertThat(service(NOW).runOnce(10)).isEqualTo(1);
        assertThat(booked).containsExactly("A/TX-A/1234CNY");
        assertThat(queriedTraces).containsExactly("trace-A:A");
        assertThat(store.status.get(1L)).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("★★ 渠道没有这笔 → 结案、不入账；还没付 / 查不通 → 按退避再查")
    void notFoundClosesAndUnpaidBacksOff() {
        open("B");
        answer = new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, null, false);
        service(NOW).runOnce(10);
        assertThat(store.status.get(1L)).isEqualTo("RESOLVED");
        assertThat(booked).isEmpty();

        open("C");
        answer = new ChannelResult.Pending(PayInstruction.none(), null);
        service(NOW).runOnce(10);
        assertThat(store.status.get(2L)).isEqualTo("PENDING");
        assertThat(store.rows.get(2L).nextAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(service(NOW.plusSeconds(59)).runOnce(10)).as("没到点不查").isZero();
    }

    @Test
    @DisplayName("★★★ 一直不明 → 退避封顶 1 小时，第 12 次转人工，不无限查下去")
    void escalatesAfterMaxAttempts() {
        assertThat(ChannelFollowUpRunner.delay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(ChannelFollowUpRunner.delay(30)).isEqualTo(Duration.ofHours(1));
        open("D");
        answer = new ChannelResult.Unknown("TIMEOUT");
        Instant t = NOW;
        for (int i = 0; i < ChannelFollowUpRunner.MAX_ATTEMPTS; i++) {
            service(t).runOnce(10);
            t = t.plus(Duration.ofHours(2));
        }
        assertThat(store.status.get(1L)).isEqualTo("ESCALATED");
        assertThat(queriedTraces).hasSize(ChannelFollowUpRunner.MAX_ATTEMPTS);
        assertThat(booked).isEmpty();
    }

    @Test
    @DisplayName("★★ 同一笔同一操作只有一条进行中的补查；结案后再出「未知」可以再开")
    void oneOpenFollowUpPerObject() {
        open("E");
        open("E");
        assertThat(store.rows).hasSize(1);
        answer = new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, null, false);
        service(NOW).runOnce(10);
        open("E");
        assertThat(store.rows).hasSize(2);
    }

    @Test
    @DisplayName("★★ 两个实例扫到同一条：只有先领到的那个去查")
    void onlyOneInstanceClaims() {
        open("F");
        FollowUpTask seen = store.due(NOW, 10).getFirst();
        assertThat(store.claim(seen, NOW.plusSeconds(120))).isTrue();
        assertThat(store.claim(seen, NOW.plusSeconds(120))).as("版本已变").isFalse();
        assertThat(Optional.ofNullable(store.rows.get(1L)).map(FollowUpTask::version)).contains(1L);
    }
}
