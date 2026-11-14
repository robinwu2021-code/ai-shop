package ai.neargo.shop.message;

import ai.neargo.shop.message.dto.MessageVOs.DiagnosticVO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商户回报附带的诊断资料清洗（TDD-商户App问题回报与版本 F2、F4）。纯函数，不碰库。
 *
 * <p><b>一律不拒单</b>：回报是出问题时的求助通道，附带资料不合规不该让求助本身失败 ——
 * 超过 300 条留最后 300 条（App 是旧的在前），单条字段超长截断，时间解析不了置空。
 *
 * <p><b>路径脱敏与 App 的 {@code redactPath} 同规则</b>（去查询串、8 位以上数字只留后 4 位、前面换成「…」）：
 * App 写入时已经脱过一次，这里再做一次是因为服务端不能假设每个调用方都是那个 App。
 */
public final class FeedbackDiagnostics {

    /** App 内存里最多 300 条（system_status.dart）；上限不能低于它，否则正常回报会被截掉最近的那段 */
    public static final int MAX_ENTRIES = 300;
    static final int METHOD_MAX = 8;
    static final int PATH_MAX = 256;
    static final int OUTCOME_MAX = 96;
    private static final int AT_MAX = 40;
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{8,}");

    private FeedbackDiagnostics() {
    }

    /** 只留四个字段、截到最后 300 条、单字段截断、{@code at} 归一。入参为 null 返回空表。 */
    public static List<DiagnosticVO> sanitize(List<DiagnosticVO> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<DiagnosticVO> tail = raw.subList(Math.max(0, raw.size() - MAX_ENTRIES), raw.size());
        List<DiagnosticVO> out = new ArrayList<>(tail.size());
        for (DiagnosticVO d : tail) {
            if (d == null) {
                continue;
            }
            out.add(new DiagnosticVO(normalizeAt(d.at()), truncate(d.method(), METHOD_MAX),
                    truncate(redactPath(d.path()), PATH_MAX), truncate(d.outcome(), OUTCOME_MAX)));
        }
        return out;
    }

    /**
     * 带时区（{@code Z} 或 {@code ±hh:mm}）→ 存成 UTC；不带时区 → <b>原样</b>，不猜时区；解析不了 → null。
     *
     * <p>不带时区的值按 UTC 硬解析的话，台北的回报整体偏 8 小时，客服拿去对服务端日志永远对不上。
     * App 现在送的正是这种（{@code DateTime.now().toIso8601String()}）。
     */
    static String normalizeAt(String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        String v = at.strip();
        try {
            return OffsetDateTime.parse(v).toInstant().toString();
        } catch (DateTimeParseException offsetMissing) {
            try {
                LocalDateTime.parse(v);
                return truncate(v, AT_MAX);
            } catch (DateTimeParseException unparseable) {
                return null;
            }
        }
    }

    /** 去查询串与片段；8 位以上的数字只留后 4 位（{@code P2611051432001} → {@code P…2001}）。 */
    static String redactPath(String path) {
        if (path == null) {
            return null;
        }
        String noQuery = path.split("[?#]", 2)[0];
        Matcher m = LONG_NUMBER.matcher(noQuery);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement("…" + digits.substring(digits.length() - 4)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 截到 {@code max} 个 UTF-16 单元，不把代理对劈成两半（劈开的半个字符写进 utf8mb4 列会报错）。
     * 库里 VARCHAR(n) 按字符计，按 UTF-16 单元截一定放得下。
     */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }
}
