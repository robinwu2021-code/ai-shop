package ai.neargo.shop.merchant.dto;

/**
 * 门店（商家侧列表与管理用）。
 *
 * @param storeNo       门店号。**一旦生成就不再变** —— 评价、订单、顾客的「我常逛的店」
 *                      都挂在它上面；换主体只换 entityNo，不换它
 * @param isDefault     是否默认店。一个主体<b>恰好一家</b>
 * @param status        ACTIVE / READONLY
 * @param payMerchantNo 这家店用哪个收款号。**空 = 用主体的默认收款号**，
 *                      不是"没配"——单通道时永远为空，行为与今天一致
 * @param payReady      这家店现在能不能收钱。端上照它显示，别自己去比状态串
 * @param staffCount    授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店
 * @param planSuspended 这家店的只读<b>是套餐降级压下来的</b>，不是商家自己停的（V150）。
 *                      两者的 {@code status} 一模一样，靠这一位区分 ——
 *                      而它决定端上给出的下一步完全不同：<b>补缴</b>还是<b>点一下启用</b>。
 *                      不给这一位的话，商家会去点那个对降级店无效的启用按钮
 * @param rating        门店评分 ×10（V155，ADR-011：评价归门店）。
 *                      <b>与主体评分是两个数</b>：主体分是各店的合成，反过来推不回去
 * @param ratingCount   计入门店评分的评价条数。<b>0 = 暂无评价</b>，不是 0 分 ——
 *                      端上按条数判空；老评价没有门店归属，所以老店在第一条新评价到来前也是 0
 */
public record StoreVO(String storeNo, String name, String address, boolean isDefault,
                      String status, String payMerchantNo, boolean payReady, int staffCount,
                      boolean planSuspended, int rating, int ratingCount,
                      /**
                       * 经营模式：SELF_OPERATED / THIRD_PARTY（门店级）。
                       * B 端「调整经营类目」面板据此决定标不标「需资质」—— 自营门店后端不判资质，
                       * 标了就是在说一件不存在的事（TDD-门店经营类目）
                       */
                      String businessMode,
                      /**
                       * 这家店所属主体是不是平台自营（{@code mch_entity.self_operated}，V329）。
                       * 「调整经营类目」面板据此决定标不标「需资质」—— 与后端免资质同一个判据。
                       * <b>不能用 businessMode 代替</b>：那一列每家新店默认都是 SELF_OPERATED，
                       * 包括第三方商家的店（TDD-门店经营类目 §10）
                       */
                      boolean selfOperated,
                      /**
                       * 门店代码（V357）：店主自己定的那一串，对外链接 {@code /s/<代码>} 用它。
                       * <b>空 = 还没设过</b>，链接回落店铺码。格式与保留词见 {@code StoreSlugs}
                       */
                      String slug,
                      /**
                       * 这家店的对外链接（完整地址，V357）。后端按 {@code shop.web.base-url}
                       * 与门店代码拼好 —— <b>端上不自己拼</b>：域名写在端上就会有两处真源，
                       * 而它已经错过一次（此前是写死的 shop.example.com，商家印了贴纸才发现）。
                       *
                       * <p><b>空 = 没配域名</b>，端上据此不显示链接（不是显示一个点不开的地址）。
                       */
                      String shareUrl,
                      /*
                       * 以下 7 项是 B 端建店（TDD-B端建店默认经营市场 AC5）用的门店经营上下文，均可为空：
                       * market 取门店所属主体（mch_entity.market），currency / currencyScale / timeZone 取 sys_market；
                       * businessTypeCode 取 mch_store，industryCode / subIndustryCode 按默认映射等于经营类型。
                       * App 建店后按「店号 + 主体号 + 市场」认出新店，缺 market 会被当成「没建成」。
                       */
                      String market,
                      String currency,
                      Integer currencyScale,
                      String timeZone,
                      String industryCode,
                      String subIndustryCode,
                      String businessTypeCode) {

    /** 不带经营上下文的旧构造：其它门店列表照旧用它。 */
    public StoreVO(String storeNo, String name, String address, boolean isDefault,
                   String status, String payMerchantNo, boolean payReady, int staffCount,
                   boolean planSuspended, int rating, int ratingCount, String businessMode,
                   boolean selfOperated, String slug, String shareUrl) {
        this(storeNo, name, address, isDefault, status, payMerchantNo, payReady, staffCount,
                planSuspended, rating, ratingCount, businessMode, selfOperated, slug, shareUrl,
                null, null, null, null, null, null, null);
    }

    /** 补上门店所属主体的经营市场（元数据取不到时只给 market）；行业与子业态按默认映射等于经营类型。 */
    public StoreVO withMarket(String market, String currency, Integer currencyScale, String timeZone) {
        return new StoreVO(storeNo, name, address, isDefault, status, payMerchantNo, payReady, staffCount,
                planSuspended, rating, ratingCount, businessMode, selfOperated, slug, shareUrl,
                market, currency, currencyScale, timeZone, businessTypeCode, businessTypeCode, businessTypeCode);
    }
}
