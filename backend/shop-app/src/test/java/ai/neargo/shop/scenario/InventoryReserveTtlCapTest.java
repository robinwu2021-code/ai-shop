package ai.neargo.shop.scenario;

import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预留时长的平台上限（M7）。
 *
 * <p><b>今天没有任何调用方能触发它</b> —— 三个 {@code reserve} 的调用点传的都是
 * 30 分钟的常量，开放 API 也只有 items / ledger / sync，没有 reserve。
 * 需求文档里写的「今天传个长值就能把货占住」<b>说过头了</b>，已改。
 *
 * <p>那为什么还要这条闸：{@code reserve} 收任意 {@code ttlSeconds}，
 * 而预留期间那批货<b>谁也买不走</b>。哪天有人把它接到外部输入上，
 * 一个很大的数就能把一家店的货占住 —— 而且不报错、不告警，
 * 只表现为「那件货一直显示卖光了」。
 *
 * <p>这条测试守的正是那一天：<b>上限存在，且超了是拒不是截断。</b>
 * 截断的话调用方以为占住了 7 天、实际只占了 1 天，而到期释放是静默的。
 *
 * <p><b>断言的是错误码，不是「抛了异常」。</b>第一版只断言 BizException，
 * 消融（把整条上限判断删掉）之后<b>照样绿</b> —— 因为探针 owner / item 根本不存在，
 * 走到后面会以 {@code STOCK_NOT_ENOUGH} 抛出。
 * 「前一道闸先拒」＝被测的那道闸压根没跑，而报错长得一模一样。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryReserveTtlCapTest {

    /** 远超默认上限（24 小时）的一个值 —— 一年 */
    private static final long ABSURD_TTL = 365L * 24 * 3600;

    @Autowired
    private ReservationService reservations;

    @Test
    @DisplayName("★★★ 超长 TTL 被拒 —— 预留期间那批货谁也买不走，而到期释放是静默的")
    void absurdTtlIsRejected() {
        assertThatThrownBy(() -> reservations.reserve(
                "OWNER-TTL-PROBE", "REF-TTL-" + System.nanoTime(),
                List.of(new ReservationService.Line("ITEM-X", "LOC-X", 1)),
                ABSURD_TTL))
                .as("一年的预留没被 TTL 上限拒掉 —— 那批货一年之内谁也买不走，且没有任何告警")
                .isInstanceOfSatisfying(BizException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                .as("拒是拒了，但不是 TTL 那道闸拒的（探针 owner 不存在，"
                                        + "走到后面会以缺货拒）—— 这一条就没测到东西")
                                .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    @DisplayName("★★ 0 与负数也拒 —— 「立刻过期的预留」等于没占，但调用方以为占上了")
    void nonPositiveTtlIsRejected() {
        for (long ttl : new long[]{0L, -1L}) {
            assertThatThrownBy(() -> reservations.reserve(
                    "OWNER-TTL-PROBE", "REF-TTL-" + System.nanoTime(),
                    List.of(new ReservationService.Line("ITEM-X", "LOC-X", 1)),
                    ttl))
                    .as("ttlSeconds=%s 没被 TTL 那道闸拒 —— 这份预留一产生就过期，而调用方以为占上了", ttl)
                    .isInstanceOfSatisfying(BizException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                    .isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }
}
