package ai.neargo.shop.pay.acquiring.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * created_at / updated_at 按 JVM 本地时区写，支付域的成功、关单、过期时间按 UTC 写。
 * 收款列表拿 UTC 日界去比 created_at 时，本地 20:00 之后（+04:00）当天新建的单会被筛掉 —— 2026-11-05 晚上实测复现。
 */
class MerchantPaymentAuditTimeTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("★★ UTC 日界换成审计列口径：+04:00 下，上海日界 16:00Z = 本地 20:00")
    void utcBoundaryMapsToLocalAuditTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
        LocalDateTime shanghaiDayStartUtc = LocalDateTime.of(2026, 11, 4, 16, 0);
        assertEquals(LocalDateTime.of(2026, 11, 4, 20, 0), MerchantPaymentQueryServiceImpl.auditLocal(shanghaiDayStartUtc));
    }

    @Test
    @DisplayName("★★ 审计列转 epoch 按本地时区：本地 20:00（+04:00）= 16:00Z")
    void auditEpochUsesLocalZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
        long expected = java.time.Instant.parse("2026-11-04T16:00:00Z").toEpochMilli();
        assertEquals(expected, MerchantPaymentQueryServiceImpl.auditEpoch(LocalDateTime.of(2026, 11, 4, 20, 0)));
    }
}
