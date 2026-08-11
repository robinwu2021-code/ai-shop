package ai.neargo.shop.spi.user;

/**
 * 任意域 → merchant：记一条商家违规。
 *
 * <p>违规这件事**发生在别的域里**——报价毁约在营销域、发假货在商品域、
 * 迟迟不发货在交易域——但**信用档案只有一份**，在商家域。
 * 让每个域为了记一笔违规而依赖整个商家域，是把「记档案」变成模块依赖。
 *
 * <p>与 {@code AuditLogPort} 的区别值得说清楚：审计记的是<b>运营做了什么</b>
 * （谁在什么时候点了哪个按钮），违规记的是<b>商家做错了什么</b>（会影响他的信用分与准入）。
 * 两者的读者、留存期限、法律意义都不同，混在一张表里之后就再也分不开。
 */
public interface MerchantGovernPort {

    /**
     * @param merchantNo 商家业务键
     * @param type       {@code FAKE_GOODS} / {@code BREACH} / {@code PRICE_FRAUD} / {@code SERVICE}。
     *                   <b>只有 BREACH 计入 breach_count</b>（与 mch_violation 的列注释一致）
     * @param action     {@code WARN} / {@code LIMIT} / {@code SUSPEND}
     * @param detail     事实描述与证据出处。<b>必填</b>——没有事实的处置在申诉时站不住
     * @param operatorNo 处置人（运营 staffNo）
     */
    void record(String merchantNo, String type, String action, String detail, String operatorNo);
}
