package ai.neargo.shop.product.dto;

import ai.neargo.shop.spi.product.GoodsVisionPort.ZipFile;
import ai.neargo.shop.spi.product.GoodsVisionPort.ZipPick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 压缩包文件 → 标准结构：**模型的分法逐文件校验，不合格的退回规则**（TDD-商品压缩包导入 AC11/AC12）。
 *
 * <p>模型会编路径、漏文件、把图片标成文案。任何一条不合格只影响那一个文件 ——
 * 整包作废的话，一个文件出错就让二十张图全回到「只认主图/详情目录」的老规则。
 *
 * <p>规则那一份由端上算好随请求带来（{@code classifyZip}，已单测），这里不再用 Java 写一遍：
 * 同一套目录规则两种语言，迟早分叉。
 */
public final class ZipPlanning {

    public static final String MAIN = "MAIN";
    public static final String DETAIL = "DETAIL";
    public static final String TEXT = "TEXT";
    public static final String IGNORE = "IGNORE";

    /** 全部来自模型 / 全部来自规则 / 都有 */
    public static final String SOURCE_LLM = "LLM";
    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_MIXED = "MIXED";

    private static final Set<String> TARGETS = Set.of(MAIN, DETAIL, TEXT, IGNORE);

    private ZipPlanning() {
    }

    /** 一个文件最终的去向。{@code order} 在同一去向内从 1 起连续；IGNORE / TEXT 之外的才有意义 */
    public record Item(String path, String target, int order) {
    }

    public record Plan(String source, List<Item> items) {
    }

    /**
     * @param files 端上送来的文件清单，**以它为准**：模型多给的路径丢掉，少给的按规则补
     * @param llm   模型的分法，可空（未启用 / 超时 / 解析失败）
     * @param rule  端上规则的分法（{@code classifyZip}），不在里面的图片按 MAIN、其余按 IGNORE
     */
    public static Plan resolve(List<ZipFile> files, List<ZipPick> llm, List<ZipPick> rule) {
        Map<String, ZipPick> fromLlm = firstByPath(llm);
        Map<String, ZipPick> fromRule = firstByPath(rule);

        record Row(ZipFile file, String target, boolean byLlm, int llmOrder, int ruleOrder, boolean cover, int seq) {
        }
        List<Row> rows = new ArrayList<>();
        int llmUsed = 0;
        for (int i = 0; i < files.size(); i++) {
            ZipFile f = files.get(i);
            ZipPick m = fromLlm.get(f.path());
            ZipPick r = fromRule.get(f.path());
            int ruleOrder = r == null ? Integer.MAX_VALUE : r.order();
            if (valid(m, f)) {
                rows.add(new Row(f, m.target(), true, m.order(), ruleOrder, m.cover(), i));
                llmUsed++;
            } else {
                rows.add(new Row(f, ruleTarget(r, f), false, Integer.MAX_VALUE, ruleOrder, false, i));
            }
        }

        // 同一去向内：模型排过的在前（按模型的序），兜底的在后（按规则的序），最后按清单原序
        Comparator<Row> byOrder = Comparator.<Row>comparingInt(r -> r.byLlm() ? 0 : 1)
                .thenComparingInt(Row::llmOrder)
                .thenComparingInt(Row::ruleOrder)
                .thenComparingInt(Row::seq);
        List<Item> items = new ArrayList<>();
        for (String target : List.of(MAIN, DETAIL, TEXT, IGNORE)) {
            List<Row> group = new ArrayList<>(rows.stream().filter(r -> r.target().equals(target)).toList());
            group.sort(byOrder);
            if (target.equals(MAIN)) {
                // 模型标的封面挪到第一张 —— 端上「页面没封面时第一张当封面」那条规则就够用，不再多一个字段
                group.stream().filter(Row::cover).findFirst().ifPresent(c -> {
                    group.remove(c);
                    group.add(0, c);
                });
            }
            boolean ordered = target.equals(MAIN) || target.equals(DETAIL);
            for (int i = 0; i < group.size(); i++) {
                items.add(new Item(group.get(i).file().path(), target, ordered ? i + 1 : 0));
            }
        }

        String source = llmUsed == 0 ? SOURCE_RULE
                : llmUsed == files.size() ? SOURCE_LLM : SOURCE_MIXED;
        return new Plan(source, items);
    }

    /** 模型的这一条能不能用：去向在枚举里；只有 txt 能当文案；图片才能进主图/详情 */
    private static boolean valid(ZipPick m, ZipFile f) {
        if (m == null || m.target() == null || !TARGETS.contains(m.target())) {
            return false;
        }
        boolean txt = isTxt(f.path());
        if (m.target().equals(TEXT)) {
            return txt;
        }
        if (m.target().equals(MAIN) || m.target().equals(DETAIL)) {
            return isImage(f.path());
        }
        return true;
    }

    private static String ruleTarget(ZipPick r, ZipFile f) {
        if (r != null && r.target() != null && TARGETS.contains(r.target())) {
            return r.target();
        }
        // 规则也没说：图片当主图（与 classifyZip「散图并进主图」一致），其余不导
        return isImage(f.path()) ? MAIN : IGNORE;
    }

    private static Map<String, ZipPick> firstByPath(List<ZipPick> picks) {
        Map<String, ZipPick> m = new HashMap<>();
        if (picks != null) {
            for (ZipPick p : picks) {
                if (p != null && p.path() != null) {
                    m.putIfAbsent(p.path(), p);
                }
            }
        }
        return m;
    }

    private static boolean isTxt(String path) {
        return path != null && path.toLowerCase().endsWith(".txt");
    }

    private static boolean isImage(String path) {
        return path != null && path.toLowerCase().matches(".*\\.(jpe?g|png|webp|gif)$");
    }
}
