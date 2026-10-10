package ai.neargo.shop.report.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一个统计口径跑到哪儿了。
 *
 * <p>读侧靠它区分两件长得一样的事：<b>「那天真的没单」与「那天的日结没跑」</b>。
 * 没有它，两者的表现都是报表里少一行。
 *
 * @param lastStatDate 已经算到哪一天（含）；{@code null} 表示从没跑过
 */
public record Watermark(
        String jobKey,
        LocalDate lastStatDate,
        LocalDateTime lastRunAt,
        int rowsWritten,
        long durationMs) {
}
