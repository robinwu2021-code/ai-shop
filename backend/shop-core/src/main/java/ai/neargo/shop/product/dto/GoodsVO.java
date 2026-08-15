package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 商品（对齐 c-app {@code Goods}）。字段名与顺序都按端上契约来。
 *
 * <p>{@code merchant} 内嵌而不是只给 {@code merchantNo}：商品卡上要显示商家名与认证标，
 * 端上再去批量查商家会让首页多一次串行请求。
 *
 * @param price 展示价 = 最低 SKU 价（端上「¥x 起」）
 */
public record GoodsVO(String goodsNo,
                      String title,
                      String subtitle,
                      String cover,
                      List<String> images,
                      String type,
                      String categoryNo,
                      MerchantBriefVO merchant,
                      double rating,
                      int ratingCount,
                      long price,
                      Long originPrice,
                      List<String> fulfillments,
                      List<SpecGroupVO> specGroups,
                      List<SkuVO> skus,
                      int sales,
                      Long cutoffAt,
                      String arrivalDesc,
                      Boolean weighed,
                      String origin,
                      Integer durationMin,
                      String storeName,
                      int limitPerUser,
                      boolean onSale,
                      /**
                       * 商家侧状态：ON_SALE / OFF_SALE / AUDITING / REJECTED。
                       *
                       * <p><b>只在 B 端下发，C 端恒为 null</b> —— 买家不需要知道
                       * 某件商品是"审核中"还是"被驳回"，那是店主和平台之间的事。
                       *
                       * <p>它不能由 {@code onSale} 推出来：下架的商品与审核中的商品
                       * {@code onSale} 都是 false，而店主对这两者要做的动作完全不同
                       * （一个是点上架，一个是等/改）。
                       */
                      String status,
                      /**
                       * 三语标题原文（{@code prd_goods.title_i18n}）。
                       *
                       * <p><b>只在 B 端下发</b>：C 端拿到的 {@code title} 已经按当前语言拍平，
                       * 给它整份译文没有用处。
                       *
                       * <p>为什么必须下发：编辑页按语言一格一格填，它拿不到原文就只能
                       * 回填当前那一格 —— 而保存是**整份覆盖**。于是
                       * <b>用中文编辑一次，英文和阿语的标题就没了</b>，
                       * 且没有任何报错：C 端回落中文，看起来一切正常。
                       */
                      java.util.Map<String, String> titleI18n,
                      /** 三语副标题原文，同 {@link #titleI18n} */
                      java.util.Map<String, String> subtitleI18n,
                      /**
                       * 最近一次驳回/强制下架的原因（V96）。
                       *
                       * <p><b>只在 B 端与运营端下发，C 端恒为 null</b>。它是审核结论里
                       * 商家能看到的那半边：审计日志只有运营看得到，没有它商家面对
                       * REJECTED 只能猜要改什么。过审时清空。
                       */
                      String auditReason,
                      /**
                       * 已配好的拼团设置：{@code {minCount, price}}。没配过为 null。
                       *
                       * <p><b>B 端「可开团的商品」整个列表靠它</b>：页面按
                       * `g.groupBuy && g.onSale` 筛。此前后端一直不下发，
                       * 于是那一栏永远是「还没有配过团购价的商品」——
                       * 商家在商品里配好了团价，<b>开团入口从来没有出现过</b>，
                       * 而两处都不报错。
                       */
                      GroupBuyConfVO groupBuy) {

    /** 商品上配好的拼团设置。开团那一步不能临时定价，价与人数都取自这里 */
    public record GroupBuyConfVO(int minCount, long price) {
    }

    /** @param ratingCount 0 条 = 还没人评过，端上据此显示「暂无评价」而不是 0 颗星 */
    public record MerchantBriefVO(String merchantNo, String name, String logo,
                                  double rating, int ratingCount,
                                  boolean verified, int breachCount) {
    }

    /**
     * @param optionCodes 与 {@link #options} 一一对应的规格编码（B-4.5）。
     *                    <b>来自平台模板的才有</b>，手输的没有 —— 有 code 的才聚合得起来
     *                    （三家店的「500g」「五百克」「0.5kg」是同一件事）。
     *                    此前它在写库那一步就被丢掉了，从接口到页面全程看不出来。
     * @param templateNo  这组规格取自哪个平台模板。历史商品靠它解释自己的 code 是什么意思
     */
    public record SpecGroupVO(String name, List<String> options,
                              List<String> optionCodes, String templateNo) {
    }

    public record SkuVO(String skuNo,
                        List<String> optionValues,
                        String spec,
                        long price,
                        Long originPrice,
                        int stock,
                        Integer nominalGram) {
    }
}
