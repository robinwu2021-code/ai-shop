package ai.neargo.shop.pay.notify.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 答复表的时间列是 DATETIME(3)：写进去的值若带微秒，MySQL 与 H2 都会四舍五入到毫秒 ——
 * 刚落的行 next_at 被进位到「未来」半毫秒，紧接着的一轮投递扫不到它（全量回归里偶发的 PENDING）。
 */
class BizNotifyClockTest {

    @Test
    @DisplayName("★★ 写库与比较用的时刻截到毫秒：不会因进位落在当前时刻之后")
    void columnTimeIsTruncatedNotRounded() {
        Instant at = Instant.parse("2026-10-27T01:00:00.123600Z");
        LocalDateTime t = BizNotifyServiceImpl.columnTime(at);
        assertThat(t).isEqualTo(LocalDateTime.parse("2026-10-27T01:00:00.123"));
        assertThat(t.getNano() % 1_000_000).isZero();
    }
}
