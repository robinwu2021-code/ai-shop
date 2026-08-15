package ai.neargo.shop.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 运营端契约里声明成 {@code string} 的那些时间字段的唯一转换处。
 *
 * <p><b>为什么不统一成毫秒数</b>：ops-web 的契约里两种都有 ——
 * 券是 {@code createdAt: number}，风控与归因是 {@code createdAt: string}。
 * 前端的 {@code fmtTime()} 两种都认，所以这不是缺陷；但**后端必须按各自声明的那种发**，
 * 否则 `gen:api` 出来的 OpenAPI 与契约对不上，而页面看起来一切正常。
 *
 * <p>输出用 UTC 的 {@code 2026-08-06T00:00:00Z} 形状，与 mock 数据一致 ——
 * 联调时两边的值能直接对比，不用先在脑子里换算时区。
 */
public final class IsoTime {

    private IsoTime() {
    }

    /** {@code LocalDateTime}（库里存的本地时间）→ ISO-8601 UTC 串。null 原样返回。 */
    public static String toIso(LocalDateTime at) {
        return at == null ? null : at.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    /** epoch 毫秒 → ISO-8601 UTC 串。 */
    public static String toIso(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    /**
     * ISO-8601 串 → {@code LocalDateTime}。
     *
     * <p>同时接受 {@code 2026-12-31T00:00:00Z}（前端表单发的那种）与不带时区的
     * {@code 2026-12-31T00:00:00}。**不接受**空串与乱码 —— 返回 null，
     * 由调用方决定这是「没填」还是「填错了」，两者的提示语不一样。
     */
    public static LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        String s = iso.trim();
        try {
            return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 不带时区的形状再试一次；仍然失败就是真的填错了
        }
        try {
            return LocalDateTime.parse(s.length() == 10 ? s + "T00:00:00" : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
