package ai.neargo.shop.product.dto;

import ai.neargo.shop.product.service.MerchantGoodsService.GoodsParam;
import ai.neargo.shop.product.service.MerchantGoodsService.SaveCommand;
import ai.neargo.shop.product.service.MerchantGoodsService.SpecGroup;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 商品差异的渲染与比较（TDD-商品编辑页-录入落点与发布历史 AC12）。
 *
 * <p><b>为什么从 {@code MerchantGoodsServiceImpl} 搬出来</b>：那边的 {@code publishPreview}
 * 比的是「线上实体 vs 提交体」，而提交历史要比的是<b>两份提交体</b>（第 N 版 vs 第 N-1 版）。
 * 两种比较要用同一套渲染，否则同一件事在两个页面上长得不一样 —— 而渲染留在那 2900 行的
 * private 里，第二个调用方只能复制一份。复制出来的那份迟早和原件漂移，
 * 而漂移的症状是「发布预览说改了规格、历史说没改」，两边都不报错。
 *
 * <p><b>这次搬移要保持行为等价</b>，所以连「每次调用新建一个 Jackson 2 {@link ObjectMapper}」
 * 这件事都照搬了 —— 换成注入的 Jackson 3 bean 是另一件事（它会改变未知字段、
 * 日期格式这些边角的处理），不能夹在一次「等价整理」里悄悄做。
 */
public final class GoodsDiffs {

    private GoodsDiffs() {
    }

    /** spec_groups JSON → 「组名: 档1/档2」的一行文本。差异是给人扫一眼的，不是契约 */
    public static String renderGroups(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var arr = new ObjectMapper().readTree(json);
            StringBuilder sb = new StringBuilder();
            for (var g : arr) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(g.path("name").asText()).append(": ");
                var opts = g.path("options");
                for (int i = 0; i < opts.size(); i++) {
                    if (i > 0) {
                        sb.append("/");
                    }
                    sb.append(opts.get(i).asText());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 读不动就原样回 JSON：差异页少一行可读文本，比整页 500 好
            return json;
        }
    }

    /** params JSON → 「维度: 值」的一行文本 */
    public static String renderParams(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var arr = new ObjectMapper().readTree(json);
            StringBuilder sb = new StringBuilder();
            for (var pnode : arr) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(pnode.path("name").asText()).append(": ").append(pnode.path("label").asText());
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /** 相等就不记一行 —— 差异清单里出现一条「没变」会让人以为自己看漏了 */
    public static void row(List<PublishPreviewVO.DiffRow> rows,
                           String field, String label, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        rows.add(new PublishPreviewVO.DiffRow(field, label, before, after));
    }

    /**
     * 两份提交体之间的差异（AC12）。
     *
     * <p>与 {@code publishPreview} 的差别只在两端的来源：那边一端是线上实体
     * （还要 dry-run 烘焙规格），这边两端都是快照，<b>不烘焙</b> ——
     * 历史是「当时提交的是什么」，不是「现在发布会变成什么」。
     * 烘焙会让同一版的历史随规格库变化而变，那就不是历史了。
     *
     * @param before 旧的那一版；null = 首版（这时什么都不回：首版没有「改了什么」）
     */
    public static List<PublishPreviewVO.DiffRow> between(SaveCommand before, SaveCommand after) {
        List<PublishPreviewVO.DiffRow> rows = new ArrayList<>();
        if (before == null || after == null) {
            return rows;
        }
        row(rows, "title", "商品名称", before.title(), after.title());
        row(rows, "subtitle", "副标题", before.subtitle(), after.subtitle());
        row(rows, "cover", "商品图", before.cover(), after.cover());
        row(rows, "category", "主营类目", before.categoryNo(), after.categoryNo());
        row(rows, "saleMode", "销售方式", before.saleMode(), after.saleMode());
        row(rows, "detail", "图文详情", before.detail(), after.detail());
        row(rows, "fulfillments", "配送方式", join(before.fulfillments()), join(after.fulfillments()));
        row(rows, "limit", "每人限购", str(before.limitPerUser()), str(after.limitPerUser()));
        row(rows, "restricted", "限购地区",
                join(before.restrictedRegions()), join(after.restrictedRegions()));
        row(rows, "spec", "规格", groupsOf(before.specGroups()), groupsOf(after.specGroups()));
        row(rows, "params", "商品参数", paramsOf(before.params()), paramsOf(after.params()));
        // SKU 按位次比价与库存 —— 档位文案的差异已经含在「规格」那一行里
        var a = before.skus() == null ? List.<MerchantSku>of() : wrap(before.skus());
        var b = after.skus() == null ? List.<MerchantSku>of() : wrap(after.skus());
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            row(rows, "sku" + i, "第 " + (i + 1) + " 档",
                    i < a.size() ? a.get(i).text() : null,
                    i < b.size() ? b.get(i).text() : null);
        }
        return rows;
    }

    /** 一档 SKU 在差异里的样子。单独一个形状，免得那段三元表达式嵌到看不懂 */
    private record MerchantSku(String text) {
    }

    private static List<MerchantSku> wrap(List<ai.neargo.shop.product.service.MerchantGoodsService.Sku> skus) {
        return skus.stream()
                .map(s -> new MerchantSku(
                        String.join(" · ", s.optionValues() == null ? List.of() : s.optionValues())
                                + " ¥" + s.price() + " 库存" + s.stock()))
                .toList();
    }

    private static String groupsOf(List<SpecGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SpecGroup g : groups) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(g.name()).append(": ")
                    .append(String.join("/", g.options() == null ? List.of() : g.options()));
        }
        return sb.toString();
    }

    private static String paramsOf(List<GoodsParam> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (GoodsParam p : params) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(p.name()).append(": ").append(p.label());
        }
        return sb.toString();
    }

    private static String join(List<String> xs) {
        return xs == null || xs.isEmpty() ? null : String.join("、", xs);
    }

    private static String str(Integer n) {
        return n == null ? null : String.valueOf(n);
    }
}
