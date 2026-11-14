package ai.neargo.shop.pay.api.guard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.api.guard.internal.InternalCallRecorder.Call;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 补偿任务的查单按比例抽样留痕（开发计划 M2.7；代码与数据库 Q-D1）：任务每两分钟扫一批、每笔查一次，全记会把请求记录撑满；
 * 建单、关单、退款与非任务来源的查单一律全记。被抽掉的只是那一行记录 —— 调用本身照常执行、结果照常返回。
 */
class JobQuerySamplingTest {

    private final List<PayApiRequestLog> rows = new ArrayList<>();

    /** 依次给出的「随机数」：可预期地验抽样 */
    private static DoubleSupplier sequence(double... values) {
        int[] i = {0};
        return () -> values[i[0]++ % values.length];
    }

    private static CallSource job() {
        return CallSources.asJob("industry-transaction-recovery", CallSources::current);
    }

    private static Call call(String action, CallSource source) {
        return new Call("PaymentPort#x", action, "MALL", "MALL_ORDER", "PYO-1", null, null, null, null, source);
    }

    @Test
    @DisplayName("★★★ 任务来源的查单：按比例记（0.1 → 十次里记随机数落在 0.1 以下的那次）；调用每次都执行、结果照常返回")
    void jobQueriesAreSampled() {
        InternalCallRecorder recorder = new InternalCallRecorder(rows::add, 0.1,
                sequence(0.05, 0.5, 0.9, 0.3, 0.2, 0.15, 0.7, 0.8, 0.6, 0.4));
        int[] executed = {0};
        for (int i = 0; i < 10; i++) {
            assertThat(recorder.record(call("QUERY", job()), () -> ++executed[0], x -> null)).isEqualTo(i + 1);
        }

        assertThat(executed[0]).isEqualTo(10);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 任务来源的建单 / 关单 / 退款，与非任务来源的查单：一律全记")
    void everythingElseIsKept() {
        InternalCallRecorder recorder = new InternalCallRecorder(rows::add, 0.1, () -> 0.99);
        for (String action : List.of("CREATE_ONLINE", "CLOSE", "REQUEST_REFUND")) {
            recorder.record(call(action, job()), () -> "ok", x -> null);
        }
        recorder.record(call("QUERY", CallSources.current()), () -> "ok", x -> null);
        recorder.record(call("QUERY", CallSources.asOutbox("ORDER_PAID", CallSources::current)), () -> "ok", x -> null);

        assertThat(rows).hasSize(5);
    }

    @Test
    @DisplayName("★★ 抽掉的查单若失败照样记 —— 出错的那次正是要看的")
    void failedJobQueryIsAlwaysKept() {
        InternalCallRecorder recorder = new InternalCallRecorder(rows::add, 0.1, () -> 0.99);
        try {
            recorder.record(call("QUERY", job()), () -> {
                throw new IllegalStateException("channel down");
            }, x -> null);
        } catch (IllegalStateException expected) {
            // 原样抛回
        }
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getOutcome()).isEqualTo("ERROR"));
    }

    @Test
    @DisplayName("★ 比例 1 = 全记，0 = 任务查单一行不记")
    void rateBounds() {
        new InternalCallRecorder(rows::add, 1.0, () -> 0.999).record(call("QUERY", job()), () -> "ok", x -> null);
        new InternalCallRecorder(rows::add, 0.0, () -> 0.0).record(call("QUERY", job()), () -> "ok", x -> null);
        assertThat(rows).hasSize(1);
    }
}
