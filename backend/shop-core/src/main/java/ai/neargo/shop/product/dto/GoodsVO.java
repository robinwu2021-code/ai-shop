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
                      /**
                       * 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染，
                       * 别拿一个空白区块占着详情页。
                       */
                      String detail,
                      /**
                       * 图文详情区的长图，按顺序全宽竖排（与 {@link #images} 的顶部轮播分开）。
                       * 空数组 = 没传过，端上不渲染这一段。
                       */
                      List<String> detailImages,
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
                       * 引用的平台标准品；空 = 自建品。<b>只在商家侧与 ops 下发，C 端恒空。</b>
                       *
                       * <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于
                       * <b>打开编辑页再保存一次就自动脱离了标准品</b> ——
                       * 商品从此不再被收敛，而界面上没有任何变化。
                       * 与 titleI18n / priceByMarket 是同一个形状的故障。
                       */
                      String stdNo,
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
                      GroupBuyConfVO groupBuy,
                      /**
                       * 商品参数（V250）：产地 / 保质期 / 材质这一类，<b>不分 SKU</b>。
                       * 买家侧原样展示；商家侧编辑页靠它回显 ——
                       * 不回显的话，「打开编辑页再保存一次就把参数清空了」。
                       */
                      List<GoodsParamVO> params,
                      /**
                       * 有未发布的草稿修改（双版本）。**只在 B 端商家视角下发，其余为 null** ——
                       * 买家不需要知道商家改没改到一半，与 {@code status} 同一条规矩。
                       */
                      Boolean hasDraft) {

    /** 一条商品参数。量纲型（功率、净重）平台不枚举值，那时只有 label */
    /**
     * @param name 维度名（「产地」「保质期」）。**买家页要显示它** ——
     *             只有 dimNo 的话详情页上是一行 {@code SD_ORIGIN: 本地}。
     *             它是下单那一刻的快照，商家事后改本店叫法不影响已卖出的商品。
     */
    public record GoodsParamVO(String dimNo, String name, String valueNo, String code, String label) {
    }

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

    /**
     * @param priceByMarket 各市场价（市场码 → 最小货币单位）。
     *
     *                      <p><b>只在商家侧 {@code /biz/goods/{no}} 下发，C 端恒空</b> ——
     *                      买家只看自己那个市场的价，给他整张表没有用处。
     *
     *                      <p>为什么必须下发：编辑页按市场逐格填，而<b>保存是整份覆盖</b>。
     *                      拿不到整张表它就只能回填当前市场那一格，于是
     *                      <b>商家改一次标题，其余市场的价格行就被删了</b>，且不报错。
     *                      与 {@code titleI18n} 是逐字同款的形状 —— 那个当年补了下发，这个没补。
     */
    /**
     * @param barcode         商品条码。**只在商家侧下发** —— 买家不需要它，
     *                        而它是商家与供应商/ERP 之间的键
     * @param merchantSkuCode 商家自有货号，同上
     * @param saleUnit        计量单位（件/斤/kg/份）。**买家侧也要** ——
     *                        「5」到底是 5 件还是 5 斤，买家同样需要知道
     */
    public record SkuVO(String skuNo,
                        List<String> optionValues,
                        String spec,
                        long price,
                        Long originPrice,
                        int stock,
                        Integer nominalGram,
                        java.util.Map<String, Long> priceByMarket,
                        /**
                         * 本店单独定的价（批 C）。<b>只在 B 端下发</b>，且**空 = 同主体价**，
                         * 不是 0 —— 端上据此显示「同总部」还是一个具体数字。
                         *
                         * <p>与门店库存回退方向相反：没设过价的店按主体价卖，
                         * 没设过库存的店按 0 卖。
                         */
                        Long storePrice,
                        /**
                         * 成本价（最小货币单位）。<b>只在商家侧下发，买家端与运营端恒空</b> ——
                         * 进货价是商家的经营秘密，平台没有理由转发给别人。
                         *
                         * <p>空 = 没填过。端上据此决定要不要显示毛利那一行。
                         */
                        Long costPrice,
                        String barcode,
                        String merchantSkuCode,
                        String saleUnit) {
    }
}
