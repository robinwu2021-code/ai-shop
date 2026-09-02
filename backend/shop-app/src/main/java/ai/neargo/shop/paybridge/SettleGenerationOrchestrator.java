package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.SettleService.SettleInput;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * <b>生成结算单的编排</b>：把「子单构成」与「那一刻的商家属性」组装好，一次交给支付域。
 *
 * <h2>为什么在这一层</h2>
 * 支付域算账要两样东西：订单的子单构成（trade）与商家的结算属性（merchant）。
 * 此前它自己去查 —— 两条都是<b>反向依赖</b>，而按
 * 「除回调外不做反向依赖，pay 只解决 pay 的核心问题」它们不该存在。
 *
 * <p>**不放在 trade 侧**：{@code shop-core} 并不依赖 {@code shop-merchant}，
 * 让它去查商家属性只是把跨域调用挪个地方，不是消除。
 * 而这一层同时够得着两边 —— 与 I1–I3/I6/I8 是同一个位置。
 *
 * <h2>五个商家属性是「快照」，不只是「参数」</h2>
 * 它们本来就要落到结算单上（{@code stl_bill} 上已有 feeBearer / payMerchantNo /
 * businessMode / fundsMode 四列）。结算单要回答的是<b>「当时按什么算的」</b>，
 * 而实时查商家表回答的是「现在是什么」—— 商家改了收款号，
 * 历史结算单不该跟着变（{@code StoreSettleFlowTest} 有一条守着）。
 *
 * <p>顺带解决了拆库时的跨库热路径：结算之后的动作（分账、提现）
 * 读的都是 stl_bill 上的快照，不再回查商家库。
 *
 * <h2>一个子单查一次，没有做批量</h2>
 * 一个订单的子单个数是「有几个商家」，实测常见 1–3 个。
 * 批量接口要新加五个 Port 方法，而它们各自的缓存与数据域行为都要重新验 ——
 * 收益（省两次同库查询）配不上那个面积。真到了需要批量的规模，
 * <b>那时的判据是这里的耗时，不是现在的猜测</b>。
 */
@Service
public class SettleGenerationOrchestrator {

    private final SettleSourcePort tradeSource;
    private final MerchantQueryPort merchants;
    private final SettleService settleService;

    public SettleGenerationOrchestrator(SettleSourcePort tradeSource, MerchantQueryPort merchants,
                                        SettleService settleService) {
        this.tradeSource = tradeSource;
        this.merchants = merchants;
        this.settleService = settleService;
    }

    /** @return 生成了几张结算单。幂等：已有的不重复生成 */
    public int generateForOrder(String orderNo) {
        List<SettleInput> inputs = tradeSource.settleSourcesOf(orderNo).stream()
                .map(this::withMerchantSnapshot)
                .toList();
        return settleService.generateForOrder(orderNo, inputs);
    }

    private SettleInput withMerchantSnapshot(SettleSourcePort.SettleSource src) {
        return new SettleInput(src,
                merchants.businessModeOf(src.merchantNo(), src.storeNo()),
                /*
                 * 收款商户号**可为空**：没进件的商家也要能出结算单，
                 * 否则那笔账就没人记了 —— 空的表现是「知道该给谁，但打不出去」。
                 */
                merchants.payMerchantNoOf(src.merchantNo(), src.storeNo()).orElse(null),
                merchants.legalFormOf(src.merchantNo()),
                merchants.fundsModeOf(src.merchantNo()),
                merchants.feeBearerOf(src.merchantNo(), src.storeNo(), src.payChannel()),
                /*
                 * 市场取自商家主体 —— 与 legalForm / fundsMode 同一层：
                 * 都是**成单那一刻的商家快照**，落进结算单之后就不再跟着主数据变。
                 * 商家改了市场不该让历史账重算。
                 */
                merchants.marketOf(src.merchantNo()));
    }
}
