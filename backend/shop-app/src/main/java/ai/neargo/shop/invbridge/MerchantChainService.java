package ai.neargo.shop.invbridge;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家链条画像：**一家一行**，从建品一路数到持续记账。
 *
 * <p>这条链上每一层都在流失，而运营端此前一层都看不见。2026-09-03 线上实测：
 * <pre>
 *   200 商家建的 SPU  →  4 上架  →  209 建账  →  6 家里只有 2 家真在记账
 * </pre>
 * 已有的三块统计（库存健康度、库存对差、获客看板）都是**某一环的快照**，
 * 没有一处是贯穿链条的漏斗。而上面这组数说明：真正的问题不在某一环内部，
 * <b>在环与环之间的落差</b>。
 *
 * <p><b>为什么它必须落在装配层</b>：六列跨三个域 —— 商家（{@code mch_entity}）、
 * 商品（{@code prd_goods}）、进销存（{@code inv_*}，独立库）。
 * 只有 {@code shop-app} 同时看得见三边。与 {@link InventoryHealthService} 同理。
 *
 * <p><b>只读，一个字节都不写。</b>
 */
public interface MerchantChainService {

    /**
     * @param limit 最多几行
     * @param stuckOnly 只要卡住的那些行。运营的常用视图是「谁需要我」，不是「所有人怎么样」
     */
    List<ChainRow> profile(int limit, boolean stuckOnly);

    /**
     * 一家商家在链条上的六个数。
     *
     * @param goods        建了几个 SPU
     * @param pendingAudit 其中待审几个
     * @param onSale       其中上架几个
     * @param items        进销存里建了几条账（物料）
     * @param firstInbound 第一笔入库的时间。**null = 一次都没进过货**
     * @param lastLedger   最近一笔流水的时间。null = 从没记过账
     * @param stuckAt      卡在哪一层，见 {@link Stuck}。null = 这条链是通的
     */
    record ChainRow(String entityNo, String merchantName,
                    long goods, long pendingAudit, long onSale, long items,
                    LocalDateTime firstInbound, LocalDateTime lastLedger,
                    String stuckAt) {
    }

    /**
     * 卡点。**取第一个断掉的环，不是所有断掉的环** ——
     * 一家商家没建品，后面五列当然全是 0，把它标成「五处都有问题」
     * 会让真正该做的那一件事（让他建第一个品）淹没在噪声里。
     */
    final class Stuck {
        /** 一个品都没建 */
        public static final String NO_GOODS = "NO_GOODS";
        /** 建了品但全卡在审核 —— **这一层的账要算在平台头上**，不是商家 */
        public static final String IN_AUDIT = "IN_AUDIT";
        /** 审完了也没上架：商家自己没点，或者被驳回后没改 */
        public static final String NOT_ON_SALE = "NOT_ON_SALE";
        /** 上架了但进销存里没有账 —— 多半是投影链路断了，见「链路健康」 */
        public static final String NO_ACCOUNT = "NO_ACCOUNT";
        /** 建了账但一次没进过货：进销存对他来说还只是个空壳 */
        public static final String NO_INBOUND = "NO_INBOUND";
        /** 进过货，但很久没动了 */
        public static final String STALE_LEDGER = "STALE_LEDGER";

        private Stuck() {
        }
    }
}
