package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.gateway.ElecColumnAi;
import ai.neargo.shop.elec.gateway.ElecColumnAi.FieldSpec;
import ai.neargo.shop.elec.support.Cells;
import ai.neargo.shop.elec.support.Columns;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.HeaderNames;
import ai.neargo.shop.elec.support.Mpn;
import ai.neargo.shop.elec.support.SheetReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认列：哪一行是表头、哪一列是什么字段。四级，<b>前一级缺什么才往下</b>：
 *
 * <ol>
 *   <li>记住的映射 —— 这家上次确认过的表，表头一字不差就整套沿用（调用方传进来）</li>
 *   <li>表头别名表 —— 本家学到的 &gt; 全局（{@link HeaderAliases}）</li>
 *   <li>大模型 —— 仅当缺料号 / 缺数量 / 缺厂牌 / 两列抢同一字段 / 找不到表头行。<b>只补缺的字段</b>，
 *       别名认出的不让它改：规则认出的是确定的，模型的是概率的，反过来会把一张认得好好的表「纠正」错</li>
 *   <li>手工 —— 仍缺料号或数量：{@code NEED_MAPPING}，供应商在页面上选</li>
 * </ol>
 *
 * <p>大模型给的每个字段都要<b>过内容校验</b>：料号列的样本真的像料号、数量列真的读得成数 ——
 * 一个错的料号列会让整张表作废或错上架，而模型认错时语气和认对时一样笃定。
 */
@Slf4j
@ConditionalOnElec
@Component
public class ColumnResolver {

    public static final String SRC_REMEMBERED = "REMEMBERED";
    public static final String SRC_ALIAS = "ALIAS";
    public static final String SRC_AI = "AI";
    public static final String SRC_MANUAL = "MANUAL";

    /** 给模型看的行数上限（找表头要前 10 行）与其中数据行上限：样本会经公网发出去，能少就少 */
    static final int AI_HEAD_ROWS = 10;
    static final int AI_DATA_ROWS = 5;
    static final int AI_CELL_CHARS = 40;
    static final int AI_MAX_COLS = 40;
    /** 校验看表头下多少行、要多大比例像 */
    static final int VERIFY_ROWS = 20;
    static final double VERIFY_RATIO = 0.6;
    /** 熔断：连续失败几次、停多久 —— 防 cdw 挂着时每次上传都白等一个超时 */
    static final int BREAKER_FAILURES = 3;
    static final Duration BREAKER_OPEN = Duration.ofMinutes(5);

    private static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("MPN", "料号 / 型号 / Part No.，如 STM32F103C8T6、GRM188R71H104KA93D"),
            new FieldSpec("MFR", "厂牌 / 品牌 / 制造商，如 TI、ST、村田、Murata"),
            new FieldSpec("QTY", "库存数量，如 2500、10K、4000pcs"),
            new FieldSpec("DC", "批号 / 年份 / Date Code，如 2338、23+"),
            new FieldSpec("PACKAGE", "封装，如 SOT-23、QFN48、0603"),
            new FieldSpec("PRICE", "单价（一个价，不是数量档），如 6.8、0.008"),
            new FieldSpec("MOQ", "最小起订量"),
            new FieldSpec("SPQ", "最小包装量 / 标准包装"),
            new FieldSpec("PACKING", "包装方式，如 卷带、托盘、管装、Tape&Reel"),
            new FieldSpec("CONDITION", "货况 / 品质，如 原装、散新、拆机"),
            new FieldSpec("CURRENCY", "币种，如 RMB、USD"),
            new FieldSpec("LEAD", "交期 / 货期，如 现货、2周"),
            new FieldSpec("REGION", "货源地 / 仓库，如 深圳、香港"));

    private final HeaderAliases aliases;
    private final ObjectProvider<ElecColumnAi> ai;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile Instant openUntil = Instant.EPOCH;

    public ColumnResolver(HeaderAliases aliases, ObjectProvider<ElecColumnAi> ai) {
        this.aliases = aliases;
        this.ai = ai;
    }

    /**
     * @param headerRow 表头行；{@code NEED_MAPPING} 且一列都认不出时，取第一行非空行给他选
     * @param source    字段 → 来源（{@link #SRC_REMEMBERED} …）
     * @param complete  认全了料号与数量
     */
    public record Resolution(int headerRow, Map<Field, Integer> map, Map<Field, String> source, Boolean taxHint,
                             List<Columns.PriceTierCol> tiers, boolean aiUsed, boolean complete) {
    }

    /** 记住的映射：这家上次确认过、表头一字不差的那张表 */
    public record Remembered(int headerRow, Map<Field, Integer> map) {
    }

    /** @return null = 整张表没有一行非空（真正的空文件） */
    public Resolution resolve(String supplierNo, List<List<String>> rows, Remembered remembered) {
        int firstNonBlank = firstNonBlank(rows);
        if (firstNonBlank < 0) {
            return null;
        }
        // ① 记住的
        if (remembered != null && remembered.map().containsKey(Field.MPN) && remembered.map().containsKey(Field.QTY)) {
            return finish(rows, remembered.headerRow(), new EnumMap<>(remembered.map()),
                    sourceAll(remembered.map(), SRC_REMEMBERED), false);
        }
        // ② 别名表
        Map<String, Field> table = aliases.lookup(supplierNo);
        Columns.Guess g = Columns.guess(rows, table);
        Map<Field, Integer> map = new EnumMap<>(Field.class);
        map.putAll(g.map());
        Map<Field, String> source = sourceAll(g.map(), SRC_ALIAS);
        int headerRow = g.headerRow();
        boolean aiUsed = false;

        // ③ 大模型
        boolean needAi = headerRow < 0 || !map.containsKey(Field.MPN) || !map.containsKey(Field.QTY)
                || !map.containsKey(Field.MFR) || !g.conflicts().isEmpty();
        if (needAi) {
            ElecColumnAi.Guess guess = askAi(rows);
            if (guess != null) {
                aiUsed = true;
                int aiHeader = guess.headerRow();
                boolean aiHeaderOk = aiHeader >= 0 && aiHeader < Math.min(AI_HEAD_ROWS, rows.size())
                        && !SheetReader.blank(rows.get(aiHeader));
                // 别名已经认全料号与数量时信别名的表头行；否则模型给的行合法就改用它，并按新行重认一遍别名
                if (!g.ok() && aiHeaderOk && aiHeader != headerRow) {
                    headerRow = aiHeader;
                    Columns.Guess at = Columns.at(rows, headerRow, table);
                    map.clear();
                    map.putAll(at.map());
                    source = sourceAll(at.map(), SRC_ALIAS);
                }
                if (headerRow >= 0) {
                    merge(rows, headerRow, map, source, guess.columns(), g.conflicts());
                }
            }
        }
        // ④ 手工：一列都没认出时，把第一行非空行当表头给他选
        if (headerRow < 0) {
            headerRow = firstNonBlank;
        }
        return finish(rows, headerRow, map, source, aiUsed);
    }

    /** 端上改了映射之后：改过的字段来源记 MANUAL，没动的保留原来源 */
    public static Map<Field, String> manualSource(Map<Field, Integer> before, Map<Field, String> beforeSource,
                                                  Map<Field, Integer> after) {
        Map<Field, String> out = new EnumMap<>(Field.class);
        for (Map.Entry<Field, Integer> e : after.entrySet()) {
            boolean same = e.getValue().equals(before.get(e.getKey()));
            out.put(e.getKey(), same && beforeSource.containsKey(e.getKey()) ? beforeSource.get(e.getKey()) : SRC_MANUAL);
        }
        return out;
    }

    private Resolution finish(List<List<String>> rows, int headerRow, Map<Field, Integer> map,
                              Map<Field, String> source, boolean aiUsed) {
        List<String> header = rows.get(headerRow);
        source.keySet().retainAll(map.keySet());
        return new Resolution(headerRow, map, source, Columns.taxHint(header, map), Columns.tierCols(header, map),
                aiUsed, map.containsKey(Field.MPN) && map.containsKey(Field.QTY));
    }

    /** 把模型的结果并进来：只补缺的字段（冲突字段除外）、列不许被两个字段占、每个字段过内容校验 */
    private void merge(List<List<String>> rows, int headerRow, Map<Field, Integer> map, Map<Field, String> source,
                       Map<String, Integer> aiCols, Set<Field> conflicts) {
        int width = rows.get(headerRow).size();
        for (Map.Entry<String, Integer> e : aiCols.entrySet()) {
            Field f = fieldOf(e.getKey());
            Integer col = e.getValue();
            if (f == null || col == null || col < 0 || col >= width) {
                continue;
            }
            boolean mayReplace = conflicts.contains(f);
            if (map.containsKey(f) && !mayReplace) {
                continue;
            }
            Set<Integer> taken = new HashSet<>(map.values());
            if (map.containsKey(f)) {
                taken.remove(map.get(f));
            }
            if (taken.contains(col)) {
                continue;
            }
            if (HeaderNames.isPrivate(StockSheetParser.cell(rows.get(headerRow), col))) {
                log.info("大模型把「{}」列认成 {}：备注 / 联系方式类的列不采纳", rows.get(headerRow).get(col), f);
                continue;
            }
            if (!verify(f, rows, headerRow, col)) {
                log.info("大模型认的 {} → 第 {} 列没过内容校验，丢掉", f, col);
                continue;
            }
            map.put(f, col);
            source.put(f, SRC_AI);
        }
    }

    /** 表头下 ≤20 行非空格子里，至少 60% 像这个字段。一个值都没有的列不认 */
    static boolean verify(Field f, List<List<String>> rows, int headerRow, int col) {
        int seen = 0;
        int good = 0;
        for (int i = headerRow + 1; i < rows.size() && seen < VERIFY_ROWS; i++) {
            String v = StockSheetParser.cell(rows.get(i), col);
            if (v == null || v.isBlank()) {
                continue;
            }
            seen++;
            if (looksLike(f, v.strip())) {
                good++;
            }
        }
        return seen > 0 && good >= Math.ceil(seen * VERIFY_RATIO);
    }

    private static boolean looksLike(Field f, String v) {
        return switch (f) {
            case MPN -> Mpn.looksLikeMpn(Mpn.norm(v));
            case QTY -> Cells.qty(v) != null;
            // 厂牌不会是纯数字：模型把数量列认成厂牌时，这一条能拦住
            case MFR -> !v.replaceAll("[\\d.,\\s]", "").isEmpty();
            case PRICE -> Cells.priceE6(v) != null;
            case MOQ, SPQ -> Cells.moq(v) != null;
            default -> true;
        };
    }

    private ElecColumnAi.Guess askAi(List<List<String>> rows) {
        ElecColumnAi port = ai.getIfAvailable();
        if (port == null || !port.isEnabled()) {
            return null;
        }
        if (Instant.now().isBefore(openUntil)) {
            log.info("大模型认列熔断中（至 {}），直接走手工", openUntil);
            return null;
        }
        ElecColumnAi.Guess g;
        try {
            g = port.guess(head(rows), FIELDS);
        } catch (RuntimeException e) {
            log.warn("大模型认列异常：{}", e.toString());
            g = null;
        }
        if (g == null || g.columns() == null) {
            if (failures.incrementAndGet() >= BREAKER_FAILURES) {
                openUntil = Instant.now().plus(BREAKER_OPEN);
                failures.set(0);
                log.warn("大模型认列连续失败 {} 次，熔断 {} 分钟", BREAKER_FAILURES, BREAKER_OPEN.toMinutes());
            }
            return null;
        }
        failures.set(0);
        return g;
    }

    /** 前 10 行，其中数据行（第一行非空行之后）最多 5 行；每格截 40 字符、最多 40 列 */
    static List<List<String>> head(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<>();
        int nonBlank = 0;
        for (int i = 0; i < Math.min(AI_HEAD_ROWS, rows.size()); i++) {
            List<String> r = rows.get(i);
            if (!SheetReader.blank(r)) {
                nonBlank++;
            }
            // 找表头要看前几行，但真正的数据行不多给：第 1 行非空行可能是表头，其后最多 5 行
            if (nonBlank > AI_DATA_ROWS + 3) {
                break;
            }
            List<String> cut = new ArrayList<>();
            for (int c = 0; c < Math.min(AI_MAX_COLS, r.size()); c++) {
                String v = r.get(c) == null ? "" : r.get(c);
                cut.add(v.length() > AI_CELL_CHARS ? v.substring(0, AI_CELL_CHARS) : v);
            }
            out.add(cut);
        }
        return out;
    }

    /** 测试用：熔断状态复位 */
    void resetBreaker() {
        failures.set(0);
        openUntil = Instant.EPOCH;
    }

    private static int firstNonBlank(List<List<String>> rows) {
        for (int i = 0; i < Math.min(AI_HEAD_ROWS, rows.size()); i++) {
            if (!SheetReader.blank(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<Field, String> sourceAll(Map<Field, Integer> map, String src) {
        Map<Field, String> m = new EnumMap<>(Field.class);
        map.keySet().forEach(f -> m.put(f, src));
        return m;
    }

    private static Field fieldOf(String code) {
        for (Field f : Field.values()) {
            if (f.name().equals(code)) {
                return f;
            }
        }
        return null;
    }
}
