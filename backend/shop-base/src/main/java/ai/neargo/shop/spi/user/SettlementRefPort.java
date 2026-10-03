package ai.neargo.shop.spi.user;

/**
 * 聚落被合并时，**把还会再用的引用改写到目标聚落**。
 *
 * <p>为什么需要这个口子：合并这件事发生在 community 域，而「谁引用了这个聚落」
 * 散在 merchant 与 product 两个域里（经营范围、商家社区池、商品社区池、渠道覆盖）。
 * community 域不能直接读写别人的表（域边界，ArchUnit 守着），所以反过来 ——
 * 由各域自己实现这个口子，community 只说「这两个号并了」。
 *
 * <p><b>只改「以后还会用」的引用</b>：决定「谁看得到什么」的那几张表。
 * 订单、批次、帖子这类历史数据一律不动 —— 被合并的那行聚落还在（置为关闭），
 * 历史单据指着它是准确的；改写反而会让事后对账的口径变了。
 */
public interface SettlementRefPort {

    /**
     * 把 {@code fromNo} 上的引用改写到 {@code intoNo}。
     *
     * <p>实现必须**幂等且能处理撞键**：两个聚落常常被同一个商家同时勾着，
     * 直接 UPDATE 会撞唯一键（entity+community）—— 撞上的那条应当删掉而不是报错，
     * 否则运营点一次合并只会看到一句「系统开小差」。
     *
     * @return 改写了几行（只用于日志与回执，不参与判断成败）
     */
    int repointSettlement(String fromNo, String intoNo);
}
