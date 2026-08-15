package ai.neargo.shop.spi.user;

/**
 * product → user：把商家的评分写回商家主体。
 *
 * <p><b>为什么单开一个 Port</b>：评分是<b>派生值</b>，真源是 {@code rvw_review} 那张明细表，
 * 而它存放的地方（{@code mch_entity}）属于 user 域。product 直连商家表会被 ArchUnit 拦下，
 * 而拦的正是这种「为了一个字段捅穿一层边界」。
 *
 * <p>只有写，没有读：读走 {@link MerchantQueryPort.MerchantBrief#rating()}。
 * 两边都能写的话，迟早有一处按自己的口径改一次，而评分改错了不会报错 ——
 * 它只是让一家店在列表里悄悄排到后面去。
 */
public interface MerchantRatingPort {

    /**
     * 覆盖写商家的评分。<b>调用方必须传重算后的全量值，不是增量</b>。
     *
     * <p>增量（+1 / 加权平均）在并发下会漂：两条评价同时落库，各自读到同一个旧值再写回，
     * 结果只算进去一条。而评分一旦漂了没有任何东西会发现 —— 它不报错，
     * 也没有对账口。所以这里的契约是「拿明细重算一遍，整个盖掉」。
     *
     * @param merchantNo 商家业务键
     * @param rating     重算后的全量评分
     */
    void updateRating(String merchantNo, Rating rating);

    /**
     * 覆盖写<b>门店</b>的评分（V155）。契约与 {@link #updateRating} 逐字相同：全量、不增量。
     *
     * <p>为什么门店那份不能由主体那份推出来：一个主体三家店，
     * 主体分是三家的合成，反过来推不回去。而顾客要看的正是「楼下那家」的分。
     *
     * @param storeNo 门店业务键。**调用方只在评价带 store_no 时调** ——
     *                老评价没有门店，不该去更新一个它不属于的店
     */
    void updateStoreRating(String storeNo, Rating rating);

    /**
     * 商家评分的全量快照。<b>全部 ×10 存整数</b>（48 = 4.8 分）——
     * 浮点数在不同库、不同语言里 round 得不一样，而这几个数是要展示给用户看的。
     *
     * @param ratingX10  综合评分
     * @param count      计入评分的评价条数（只算审核通过的）
     * @param goodsX10   商品维度
     * @param serviceX10 服务维度
     * @param speedX10   履约速度维度
     */
    record Rating(int ratingX10, int count, int goodsX10, int serviceX10, int speedX10) {
    }
}
