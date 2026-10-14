package ai.neargo.shop.pay.service.recon;

import ai.neargo.shop.pay.entity.StlBankFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网银导出 CSV → {@link StlBankFlow}（TDD-供应商结算与双轨资金 §10.3）。
 *
 * <p><b>各家银行的方言停在这里</b>，一个字节都不流进比对逻辑：列名别名、
 * 日期三种写法、借贷标志四套说法、金额里的千分位与负号、对方账号的掩码，
 * 全在这一层归一化完。{@link PayoutReconAxis} 那边只认 {@code OUT/IN} 与恒正的分。
 *
 * <p><b>逐行判，不是一行坏就整份拒绝。</b>网银导出常带页脚合计行、空行和
 * 一两条格式异样的记录；整份拒绝会把财务推进「删一行传一次」的循环，
 * 而他们手里是一份不该被编辑的原始凭据。
 *
 * <p>纯函数，不碰库 —— 所以银企直连接上时，换掉的是取数那一段，这里一行不动。
 */
public final class BankFlowCsvParser {

    private BankFlowCsvParser() {
    }

    /** 一行没解析成功；{@code line} 是文件里的行号（1 起，含表头），给财务对着原始文件看 */
    public record Failure(int line, String reason) {
    }

    public record ParseResult(List<StlBankFlow> rows, List<Failure> failures) {
    }

    /** 列别名。键是归一化后的列名（去空格、去 BOM、小写），值是我们的字段 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    private static void alias(String field, String... names) {
        for (String n : names) {
            ALIASES.put(n, field);
        }
    }

    static {
        alias("flowNo", "流水号", "交易流水号", "凭证号", "业务参考号", "银行流水号");
        alias("tradeDate", "交易日期", "记账日期", "日期", "交易时间");
        alias("direction", "借贷标志", "收支方向", "方向", "借贷方向");
        alias("amount", "金额", "发生额", "交易金额", "发生额(元)", "金额(元)");
        alias("counterpartyName", "对方户名", "收款人", "对方名称", "对方账户名称");
        alias("counterpartyAccount", "对方账号", "收款账号", "对方account", "对方账户账号");
        alias("remark", "摘要", "附言", "用途", "备注");
    }

    public static ParseResult parse(String csv) {
        List<StlBankFlow> rows = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            failures.add(new Failure(0, "文件是空的"));
            return new ParseResult(rows, failures);
        }
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        // 表头不一定在第一行：有些网银在前面塞了「账号/户名/查询区间」几行说明
        int headerAt = -1;
        Map<String, Integer> col = Map.of();
        for (int i = 0; i < lines.length && i < 20; i++) {
            Map<String, Integer> c = headerOf(lines[i]);
            if (c.containsKey("flowNo") && c.containsKey("amount")) {
                headerAt = i;
                col = c;
                break;
            }
        }
        if (headerAt < 0) {
            failures.add(new Failure(0, "找不到表头：至少要有「流水号」和「金额」两列"));
            return new ParseResult(rows, failures);
        }

        for (int i = headerAt + 1; i < lines.length; i++) {
            String raw = lines[i];
            if (raw == null || raw.isBlank()) {
                continue;   // 空行不算失败
            }
            List<String> cells = splitCsvLine(raw);
            // 页脚合计行：列数对不上或流水号为空，且整行没有可用的流水号 —— 静默跳过
            String flowNo = cell(cells, col, "flowNo");
            if (flowNo.isBlank()) {
                continue;
            }
            try {
                rows.add(toRow(cells, col, flowNo));
            } catch (IllegalArgumentException e) {
                failures.add(new Failure(i + 1, e.getMessage()));
            }
        }
        return new ParseResult(rows, failures);
    }

    private static StlBankFlow toRow(List<String> cells, Map<String, Integer> col, String flowNo) {
        StlBankFlow f = new StlBankFlow();
        f.setFlowNo(flowNo);
        f.setTradeDate(normalizeDate(cell(cells, col, "tradeDate")));

        String amountRaw = cell(cells, col, "amount");
        long minor = parseAmountMinor(amountRaw);
        String dirCell = cell(cells, col, "direction");
        String dir = normalizeDirection(dirCell);
        if (dir == null) {
            /*
             * 方向有两个来源：借贷标志列、金额负号。**两处都有时以列为准**；
             * 两处都没有就整行失败 —— 猜方向等于猜「这笔钱是进是出」，
             * 猜错会把一笔收款报成「银行多付了一笔」。
             */
            if (amountRaw.trim().startsWith("-")) {
                dir = StlBankFlow.OUT;
            } else {
                throw new IllegalArgumentException("看不出收支方向：借贷标志列是「%s」，金额也没有负号"
                        .formatted(dirCell));
            }
        }
        f.setDirection(dir);
        f.setAmountMinor(Math.abs(minor));
        f.setCounterpartyName(trimTo(cell(cells, col, "counterpartyName"), 128));
        f.setCounterpartyAccountMasked(mask(cell(cells, col, "counterpartyAccount")));
        f.setRemark(trimTo(cell(cells, col, "remark"), 255));
        return f;
    }

    private static Map<String, Integer> headerOf(String line) {
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> cells = splitCsvLine(line);
        for (int i = 0; i < cells.size(); i++) {
            String k = cells.get(i).replace("﻿", "").replaceAll("\\s", "").toLowerCase();
            String field = ALIASES.get(k);
            if (field != null && !col.containsKey(field)) {
                col.put(field, i);
            }
        }
        return col;
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String field) {
        Integer i = col.get(field);
        if (i == null || i >= cells.size() || cells.get(i) == null) {
            return "";
        }
        return cells.get(i).replace("﻿", "").trim();
    }

    /** 逗号分隔，支持双引号包裹与 {@code ""} 转义 —— 附言里带逗号是常态 */
    static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    /** {@code yyyy/M/d}、{@code yyyyMMdd}、{@code yyyy-MM-dd}、带时分秒 → {@code yyyy-MM-dd} */
    static String normalizeDate(String s) {
        String v = s.replace("﻿", "").trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("交易日期是空的");
        }
        int sp = v.indexOf(' ');
        if (sp > 0) {
            v = v.substring(0, sp);   // 「2026-09-20 14:03:11」只要日期那一半
        }
        v = v.replace('/', '-').replace('.', '-');
        if (v.matches("\\d{8}")) {
            return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6);
        }
        String[] p = v.split("-");
        if (p.length != 3) {
            throw new IllegalArgumentException("看不懂的日期：" + s);
        }
        try {
            return "%04d-%02d-%02d".formatted(
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("看不懂的日期：" + s);
        }
    }

    /** 「1,234.50」「¥1234.5」「-1234.50」→ 分。**元转分走字符串**，不碰 double */
    static long parseAmountMinor(String s) {
        String v = s.replace("﻿", "").trim()
                .replace(",", "").replace("¥", "").replace("￥", "").replace(" ", "");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("金额是空的");
        }
        try {
            return new java.math.BigDecimal(v)
                    .movePointRight(2)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("看不懂的金额：" + s);
        }
    }

    /** 借·支出·付款·D·OUT → OUT；贷·收入·进账·C·IN → IN；看不出返回 null */
    static String normalizeDirection(String s) {
        String v = s.replace("﻿", "").replaceAll("\\s", "").toUpperCase();
        if (v.isEmpty()) {
            return null;
        }
        if (v.contains("借") || v.contains("支出") || v.contains("付款") || v.contains("出账")
                || v.equals("D") || v.equals("OUT") || v.equals("DR")) {
            return StlBankFlow.OUT;
        }
        if (v.contains("贷") || v.contains("收入") || v.contains("进账") || v.contains("入账")
                || v.equals("C") || v.equals("IN") || v.equals("CR")) {
            return StlBankFlow.IN;
        }
        return null;
    }

    /**
     * 对方账号**在导入时就掩码**，全号一个字节都不入库 ——
     * 对账不需要全号，而存下来就多一个泄露面。
     */
    static String mask(String account) {
        String v = account.replaceAll("\\s", "");
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() <= 4) {
            return "****";
        }
        return "****" + v.substring(v.length() - 4);
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
