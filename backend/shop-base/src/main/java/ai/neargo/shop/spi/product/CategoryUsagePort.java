package ai.neargo.shop.spi.product;

/**
 * merchant → product：类目对授权码的引用情况。
 *
 * <p><b>只暴露计数，不暴露类目</b>：调用方要的从来不是「哪些类目挂了这个码」，
 * 而是「这个码还能不能停」。返回类目列表的话，商家域会顺手用上类目的其它字段，
 * 将来商品域改一列就要改两个模块。
 *
 * <p><b>为什么必须有它</b>：停用一个还被类目引用的授权码，会让那些类目变成
 * 「要求一个已经停用的码」—— 也就是<b>永远拒绝所有人</b>，而商家看到的只是
 * 「你还没有资质授权」，去哪申请没人知道。V5 的注释里写过这个形状：
 * 一个只会拒绝的校验比没有校验更糟，因为它看起来在工作。
 */
public interface CategoryUsagePort {

    /**
     * 有多少个<b>未归档</b>的类目挂着这个 {@code required_code}。
     *
     * <p>已归档的不算：它们本来就不出现在商家的类目选择器里，
     * 把它们算进来只会让一个早该停用的码永远停不掉。
     */
    long countByRequiredCode(String requiredCode);

    /**
     * 这家主体在某个类目下有几件商品。<b>删门店货架前要用它</b> ——
     * 不拦的话那些商品会挂在一个已经不存在的货架上，店铺页里就此消失，
     * 而商家在商品列表里还看得到它们。
     *
     * <p>按主体算而不按门店：商品挂主体（ADR-011），门店只是投影。
     */
    long countGoodsInCategory(String entityNo, String categoryNo);

    /**
     * 这家主体<b>在架</b>商品里，有几件的类目要求这些码之一。
     *
     * <p>撤码时用它算代价：撤掉一个码，那些商品下次上架就会被闸门拒 ——
     * 运营在按下确认之前要看得见这个数。看不见的话，一次「顺手收紧」
     * 会在几天后变成商家的「我的货怎么上不去了」，而两件事没人会联系起来。
     *
     * <p>{@code codes} 为空返回 0：没撤任何码就没有影响面。
     */
    long countOnShelfGoodsRequiring(String entityNo, java.util.Collection<String> codes);

    /** 经营这个类目要的授权码；<b>空 = 无门槛</b>。与商品域的上架校验读的是同一个字段。 */
    String requiredCodeOf(String categoryNo);

    /** 平台类目名，展示用。查无此项返回 {@code null}。 */
    String nameOf(String categoryNo);

    /** 这个类目是不是启用中。**已归档的不能再被选进货架，也不能被新建商品引用**。 */
    boolean isActive(String categoryNo);
}
