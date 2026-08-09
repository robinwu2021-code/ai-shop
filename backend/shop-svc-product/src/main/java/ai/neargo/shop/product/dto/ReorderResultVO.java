package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 一键再来一单的结果（C-ST-03）。
 *
 * <p><b>丢了什么必须说清楚</b>：悄悄少加是最糟的处理 ——
 * 用户以为整单都买到了，到货才发现少东西，而那时已经无从追溯是哪一步丢的。
 *
 * @param added   成功加入购物车的件数
 * @param dropped 已失效、没加进购物车的商品名
 * @param priceUp 涨价了但仍加入的商品名 —— 老客对价格敏感，悄悄涨价比涨价本身更伤复购
 */
public record ReorderResultVO(int added, List<String> dropped, List<String> priceUp) {
}
