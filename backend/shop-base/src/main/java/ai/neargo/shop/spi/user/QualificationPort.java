package ai.neargo.shop.spi.user;

/**
 * product → user：查主体<b>此刻</b>有没有某类有效资质。
 *
 * <p><b>为什么要走 Port</b>：判定发生在 product 域（这件商品在这家店支持哪些支付方式），
 * 而 {@code mch_qualification} 在 merchant 域。直接依赖会造成域间耦合 ——
 * 这个仓库已经撞出过 {@code merchant → StoreShelfPort → MerchantGoodsService
 * → GoodsService → merchant} 的构造环，Spring 默认禁止循环引用，整个上下文起不来。
 *
 * <p>与 {@link MerchantQueryPort} 同一条规矩：<b>只暴露一个布尔判定，不返回实体</b>。
 * Port 一旦返回实体，模块边界就名存实亡。
 */
public interface QualificationPort {

    /** 营业执照。 */
    String BUSINESS_LICENSE = "BUSINESS_LICENSE";
    /** 食品经营许可。 */
    String FOOD_PERMIT = "FOOD_PERMIT";

    /**
     * 这个主体<b>此刻</b>有没有一张该类型的有效资质。
     *
     * <p><b>判据是「未过期」，不是「状态字段等于 VALID」。</b> 两个理由：
     *
     * <ol>
     *   <li>{@code MchQualification} 的类注释里记着同一个坑的另一半：上架校验读的是
     *       审核时写死的 {@code category_codes}，<b>证过期了那串编码不会变</b>，
     *       商家照样上架、系统照样放行，平台收不到任何信号。这里不能重犯。</li>
     *   <li>把 {@code status} 置成 {@code EXPIRED} 的是定时任务，而
     *       <b>生产只跑 {@code api,ops} 两个 profile，没有 {@code worker}，
     *       定时任务在生产根本不跑</b> —— 依赖它等于依赖一件不会发生的事。</li>
     * </ol>
     *
     * <p>所以实现里同时看 {@code status != REVOKED} 与 {@code expire_at} 现算：
     * 吊销是人为动作、必须立刻生效；过期是时间到了、按时间算最准。
     *
     * @param entityNo 经营主体（<b>不是门店</b>）—— 证是主体的，店是主体开的
     * @param qualType {@link #BUSINESS_LICENSE} 等
     */
    boolean hasValidQualification(String entityNo, String qualType);
}
