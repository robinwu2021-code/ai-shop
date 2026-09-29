package ai.neargo.shop.elec.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格单元格 → 值。供应商的表五花八门，这里只做<b>认得出就认，认不出就当空</b>，
 * 不猜：数量认不出这一行就不上架（空 ≠ 0），价格认不出就当没报价。
 */
public final class Cells {

    private static final Pattern QTY = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(K|千|W|万|M)?$");
    private static final Pattern YYWW = Pattern.compile("^(\\d{2})(\\d{2})$");
    private static final Pattern YYYY = Pattern.compile("(20\\d{2})");
    private static final Pattern YY_PLUS = Pattern.compile("(\\d{2})\\+");
    private static final Pattern YY_ANY = Pattern.compile("(?<!\\d)(\\d{2})(?!\\d)");

    private Cells() {
    }

    /**
     * 数量：{@code 5,000} {@code 5000} {@code 5K} {@code 1.2万} {@code 5000pcs}。
     *
     * @return 认不出或 ≤0 时为 null
     */
    public static Long qty(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT)
                .replace(",", "").replace("，", "")
                .replace("PCS", "").replace("PC", "").replace("个", "").replace("片", "").replace("只", "")
                .trim();
        Matcher m = QTY.matcher(s);
        if (!m.matches()) {
            return null;
        }
        BigDecimal n = new BigDecimal(m.group(1));
        String unit = m.group(2);
        if (unit != null) {
            n = n.multiply(switch (unit) {
                case "K", "千" -> BigDecimal.valueOf(1_000);
                case "W", "万" -> BigDecimal.valueOf(10_000);
                default -> BigDecimal.valueOf(1_000_000);
            });
        }
        long v = n.setScale(0, RoundingMode.DOWN).longValue();
        return v > 0 ? v : null;
    }

    /**
     * 单价 → 百万分之一元：{@code ¥6.20} {@code 6.2元} {@code 0.0015}。
     *
     * @return 认不出或 ≤0 时为 null（= 没报价）
     */
    public static Long priceE6(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace("¥", "").replace("￥", "").replace("元", "").replace("RMB", "")
                .replace("rmb", "").replace(",", "").replace("，", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.signum() <= 0) {
                return null;
            }
            return v.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** 起订量：认不出为 null */
    public static Integer moq(String raw) {
        Long v = qty(raw);
        return v == null || v > Integer.MAX_VALUE ? null : v.intValue();
    }

    /**
     * 批号 → 年份。买家只看得到年份（精确批号能认出是谁家的货）。
     *
     * <ul>
     *   <li>{@code 2338}（年周）→ 2023；{@code 2019} → 2020（四位一律按年周）</li>
     *   <li>{@code 2023-05} / {@code 2023年} → 2023</li>
     *   <li>{@code 23+} → 2023</li>
     *   <li>{@code 24/25} → 2025（取最新的那个）</li>
     * </ul>
     * 算出来晚于今年的一律丢弃（{@code 2599} 不是 2025 年第 99 周）。
     */
    public static Integer dcYear(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int thisYear = Year.now().getValue();
        /*
         * 恰好四位数字按<b>年周</b>读，这是行业惯例：{@code 2019} 是 2020 年第 19 周，不是 2019 年。
         * 所以它必须排在「找 20xx」之前 —— 反过来的话 2019、2023 这类批号全部读错。
         */
        Matcher yyww = YYWW.matcher(s);
        if (yyww.matches()) {
            int week = Integer.parseInt(yyww.group(2));
            return week >= 1 && week <= 53 ? max(null, 2000 + Integer.parseInt(yyww.group(1)), thisYear) : null;
        }
        Integer best = null;
        Matcher full = YYYY.matcher(s);
        while (full.find()) {
            best = max(best, Integer.parseInt(full.group(1)), thisYear);
        }
        if (best != null) {
            return best;
        }
        Matcher plus = YY_PLUS.matcher(s);
        if (plus.find()) {
            return max(null, 2000 + Integer.parseInt(plus.group(1)), thisYear);
        }
        Matcher any = YY_ANY.matcher(s);
        while (any.find()) {
            best = max(best, 2000 + Integer.parseInt(any.group(1)), thisYear);
        }
        return best;
    }

    private static Integer max(Integer cur, int candidate, int thisYear) {
        if (candidate < 2000 || candidate > thisYear) {
            return cur;
        }
        return cur == null ? candidate : Math.max(cur, candidate);
    }

    /**
     * 阶梯价：{@code [{"minQty":1,"e6":1850000},…]}，按 minQty 升序、同一档取先出现的那个。
     * 只有一档也算阶梯（很多表就只有一列单价）。
     *
     * @return 一档都认不出时空列表
     */
    public static java.util.List<long[]> tiers(java.util.List<long[]> raw) {
        java.util.Map<Long, Long> byQty = new java.util.LinkedHashMap<>();
        for (long[] t : raw) {
            byQty.putIfAbsent(t[0], t[1]);
        }
        return byQty.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> new long[]{e.getKey(), e.getValue()})
                .toList();
    }

    /** 截断到列宽。null 与空白都当 null */
    public static String text(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
