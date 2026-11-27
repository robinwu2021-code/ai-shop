package ai.neargo.shop.merchant.paymerchant;

import ai.neargo.shop.merchant.paymerchant.entity.MchPaymentMerchant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 商户核心读写「收款商户」的唯一入口。
 *
 * <p>为什么要有它：收款商户（钱进哪个户）现在有三份 —— 商户域的旧表、进程内支付域的新模型、
 * 独立支付服务 near-pay 的。以谁为准还没定（商户域设计方案评审 R8）。商户核心原来有 17 处直接读写旧表，
 * 定下来那天要一处处改；收到这里之后，只换这个接口的实现。
 *
 * <p>本接口<b>不改变任何行为</b>：每个方法就是原来那一处查询，条件、排序、取第一条的规则逐字保留。
 * 数据域（运营只看某商家）照旧由调用方决定是否绕过：除了 {@link #pageForOps}，其余方法都不碰数据域。
 */
public interface CollectingMerchants {

    /** 主体下所有收款记录（任意状态、任意门店），按主键。 */
    List<MchPaymentMerchant> all(String entityNo);

    /** 主体下所有收款记录，按门店号排序（额度页）。 */
    List<MchPaymentMerchant> allByStore(String entityNo);

    /** 主体下已开通（ACTIVE）的收款记录。 */
    List<MchPaymentMerchant> active(String entityNo);

    /** 主体是否有任何一条已开通的收款记录 —— 「能不能收钱」。 */
    boolean hasActive(String entityNo);

    /** 主体是否有任何收款记录（任意状态）。 */
    boolean exists(String entityNo);

    /** 主体下某个收款号对应的记录。 */
    List<MchPaymentMerchant> byPayMerchantNo(String entityNo, String payMerchantNo);

    /** 主体下某个收款号对应的第一条记录。 */
    Optional<MchPaymentMerchant> firstByPayMerchantNo(String entityNo, String payMerchantNo);

    /** 主体下某收款号、某通道、已开通的那条。 */
    Optional<MchPaymentMerchant> activeByPayMerchantNo(String entityNo, String payMerchantNo, String channel);

    /**
     * 门店能用的已开通收款号：先本店专属，没有再取主体级（store_no 为空串）。不分通道。
     */
    Optional<String> activePayMerchantNoForStore(String entityNo, String storeNo);

    /**
     * 门店某通道的收款记录（任意状态）：先本店专属，没有再取主体级。{@code storeNo} 为空只取主体级。
     */
    Optional<MchPaymentMerchant> forStoreAndChannel(String entityNo, String storeNo, String channel);

    /** 某门店（或主体级，传 {@link MchPaymentMerchant#ENTITY_LEVEL}）的第一条记录，不分通道与状态。 */
    Optional<MchPaymentMerchant> firstForStore(String entityNo, String storeNoOrEntityLevel);

    /**
     * 运营端进件看板：按状态（可多个）、通道、关键词（主体号、收款号、子商户号，或店名命中的主体号）筛，
     * 卡住的排前面、同态新到旧。<b>数据域照常生效</b>：配了「只看某商家」的运营只看到那一家。
     */
    Page<MchPaymentMerchant> pageForOps(Collection<String> statuses, String payChannel, String keyword,
            Collection<String> entityNosMatchingName, long page, long size);

    /** 新建一条（主体开通时的默认进件行）。 */
    void insert(MchPaymentMerchant row);

    /** 按主键整行更新（带乐观锁）。 */
    void update(MchPaymentMerchant row);

    /**
     * 累加本期已用额度：跨期则从这一笔重新计。原子更新、版本号加一。
     *
     * @return 更新的行数；不是 1 说明行在读与写之间没了，调用方应当失败重推，不能静默少记
     */
    int addQuotaUsed(long id, String period, long amountMinor);
}
