package ai.neargo.shop.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从一段自由文字里抽**确定性字段**——商品快速录入的规则层（TDD-商品快速录入 §2，AC5/AC6）。
 *
 * <p><b>为什么要有它，而不是全交给 LLM：</b>价格、快递、不发货区域这几类是确定的，
 * 正则一抓一个准，而 LLM 会把「10元」读成别的数字——价格错是真金白银。
 * 规则先把这些抓住，<b>LLM 不可达时它们一个都不丢</b>（兜底）；LLM 回来后只负责
 * 「单果140g+ 算哪个规格维度」这类需要语义的归类，且**不许改写规则已抽到的价格**。
 *
 * <p><b>纯函数、无依赖</b>：不碰库、不碰 AI、不读配置，所以能单测、能上棘轮。
 * 规格的语义归类**不在这里**——它要类目模板与语义，是 LLM 的事（P2）。
 * 这里只抽「原子事实」：几个价、几个重量词、哪几家快递、不发哪些地方。
 */
public final class GoodsTextRuleParser {

    private GoodsTextRuleParser() {
    }

    /**
     * 价格：必须带「元」或「￥/¥」。
     *
     * <p><b>带货币符号才算价</b>——否则「140g」里的 140、「4.5斤」里的 4.5 都会被当成价。
     * 价格错比抽不到更糟：抽不到商家自己填，抽错了他可能没注意就按错价卖了。
     */
    private static final Pattern PRICE = Pattern.compile(
            "(?:￥|¥)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元");

    /** 重量/净重/容量：带单位才算。只抽原文，不换算——换算成同一单位要先知道商家想要哪个，那是确认层的事。 */
    private static final Pattern WEIGHT = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:kg|千克|g|克|斤|两|ml|毫升|l|升)", Pattern.CASE_INSENSITIVE);

    /** 承运商词表。固定集合——新词要显式加进来，不靠「XX快递」泛匹配（那会把「次日快递」也收进来）。 */
    private static final List<String> CARRIERS = List.of(
            "顺丰", "圆通", "中通", "申通", "韵达", "邮政", "EMS", "京东", "德邦", "极兔", "百世", "天天");

    /** 省级行政区简称——不发货区域的候选集。连写（新疆西藏海南）靠逐个 contains 拆开。 */
    private static final List<String> REGIONS = List.of(
            "北京", "天津", "上海", "重庆", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
            "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东",
            "广西", "海南", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏",
            "新疆", "台湾", "香港", "澳门");

    /** 「不发货」的几种说法。命中其一，才去它前面找地名——否则「新疆哈密瓜」里的新疆会被误收。 */
    private static final Pattern NO_SHIP = Pattern.compile("不发货|不包邮|不发|除外|不配送");

    public record Result(List<Long> pricesMinor,
                         List<String> weights,
                         List<String> carriers,
                         List<String> excludeRegions) {
    }

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Result(List.of(), List.of(), List.of(), List.of());
        }
        return new Result(prices(text), weights(text), carriers(text), excludeRegions(text));
    }

    private static List<Long> prices(String text) {
        List<Long> out = new ArrayList<>();
        Matcher m = PRICE.matcher(text);
        while (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(2);
            // 元 → 分。用 BigDecimal 避免 10.1 元变成 1009 分那种浮点误差
            out.add(new BigDecimal(num).multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact());
        }
        return out;
    }

    private static List<String> weights(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WEIGHT.matcher(text);
        while (m.find()) {
            out.add(m.group().replaceAll("\\s+", ""));
        }
        return out;
    }

    private static List<String> carriers(String text) {
        // LinkedHashSet：去重但保留出现顺序，商家读起来是他写的那个次序
        List<String> out = new ArrayList<>();
        for (String c : CARRIERS) {
            if (text.contains(c)) {
                out.add(c);
            }
        }
        // 按在文本中出现的先后排——商家读到的是他写的那个次序，不是词表的次序
        out.sort((a, b) -> Integer.compare(text.indexOf(a), text.indexOf(b)));
        return out;
    }

    private static List<String> excludeRegions(String text) {
        Matcher m = NO_SHIP.matcher(text);
        if (!m.find()) {
            return List.of();
        }
        /*
         * 只在「不发货」关键词**前面**的一段窗口里找地名。
         *
         * 取前 40 字：地名清单一般紧挨着「不发货」写在它前头（「新疆西藏海南不发货」）。
         * 不限窗口的话，一句「全国包邮，新疆的客户请注意」也会把新疆收进不发货——
         * 而那句话根本没说不发。
         */
        int end = m.start();
        int start = Math.max(0, end - 40);
        String window = text.substring(start, end);
        List<String> out = new ArrayList<>();
        for (String r : REGIONS) {
            if (window.contains(r)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Integer.compare(window.indexOf(a), window.indexOf(b)));
        return out;
    }
}
