package ai.neargo.shop.product.dto;

import ai.neargo.shop.spi.product.GoodsVisionPort.ParamHint;
import ai.neargo.shop.spi.product.GoodsVisionPort.ParamKV;

import java.util.List;
import java.util.Map;

/**
 * 模型抽出来的参数 → 这个品类的**标准参数**（TDD-商品快速录入-品类感知与逐项确认 §7 第 2 条）。
 *
 * <p><b>为什么要这一步</b>：2026-10-07 在生产草稿里看到，「单果140g+ 净重4.5斤」识别之后
 * 落成了两条参数 {@code {dimNo:"单果重量"}}、{@code {dimNo:"净重"}} —— 维度号是模型自己起的中文名。
 * 值确实写进了参数，但**不是这个商品的那个参数**：不参与筛选、与标准的「净含量」是两个键，
 * 而界面上看起来一切正常。
 *
 * <p>核对顺序（前一步命中就不看后面）：
 * <ol>
 *   <li><b>模型给的维度号在清单里</b> —— 首选。模型拿着清单选，这是数据驱动的那条路</li>
 *   <li><b>名称与清单里某一项完全相同</b></li>
 *   <li><b>重量的几种常见叫法</b> → 标准维度号（见 {@link #ALIAS}）。只是安全网：模型偶尔不照抄维度号</li>
 *   <li>都对不上 → <b>原样保留为自由参数</b>，与此前行为一致，什么都不丢</li>
 * </ol>
 *
 * <p>2、3 两步命中的维度号<b>也必须在清单里</b>：别名表说「净重 = 净含量」，
 * 但这个品类若没绑净含量，就不该凭空落进去。
 */
public final class ParamMapping {

    private ParamMapping() {
    }

    /**
     * 重量的常见叫法 → 标准维度号。**只收重量这几个** —— 它们是最易混、也是这次出事的那几个：
     * 「单果 140g」与「净重 4.5 斤」都是重量，落错一个，运费、规格、展示全跟着错。
     *
     * <p>这是一张写死的表，与「参数由品类决定」的原则有张力，所以它只当安全网：
     * 主路是模型拿着品类清单直接选维度号（第 1 步），走到这里说明模型没照抄。
     */
    private static final Map<String, String> ALIAS = Map.of(
            "净重", "SD_NET_CONTENT",
            "净重量", "SD_NET_CONTENT",
            "净含量", "SD_NET_CONTENT",
            "单果重量", "SD_UNIT_WEIGHT",
            "单果重", "SD_UNIT_WEIGHT",
            "单个重量", "SD_UNIT_WEIGHT",
            "毛重", "SD_GROSS_WEIGHT",
            "毛重量", "SD_GROSS_WEIGHT");

    /**
     * 核对后的结果。
     *
     * @param dimNo  落到的维度号。{@code mapped=false} 时是原文叫法（自由参数的旧形状）
     * @param name   展示名。对上了就用<b>标准名称</b>（「净含量」），不用原文叫法（「净重」）
     * @param mapped 是否对到了本品类的标准参数
     */
    public record Resolved(String dimNo, String name, boolean mapped) {
    }

    public static Resolved resolve(ParamKV p, List<ParamHint> hints) {
        if (hints != null && !hints.isEmpty()) {
            // ① 模型选的维度号确实在清单里
            if (p.dimNo() != null) {
                for (ParamHint h : hints) {
                    if (h.dimNo().equals(p.dimNo())) {
                        return new Resolved(h.dimNo(), h.name(), true);
                    }
                }
            }
            // ② 名称完全相同
            for (ParamHint h : hints) {
                if (h.name().equals(p.name())) {
                    return new Resolved(h.dimNo(), h.name(), true);
                }
            }
            // ③ 重量别名 —— 目标也必须在本品类清单里
            String alias = ALIAS.get(p.name());
            if (alias != null) {
                for (ParamHint h : hints) {
                    if (h.dimNo().equals(alias)) {
                        return new Resolved(h.dimNo(), h.name(), true);
                    }
                }
            }
        }
        // ④ 对不上：原样保留为自由参数（此前的行为），什么都不丢
        return new Resolved(p.name(), p.name(), false);
    }
}
