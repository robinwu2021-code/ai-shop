package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.WxLogisticsTypes;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把一行上报台账补全成一次真正的上报所需要的四件东西：
 * 商品描述、运单号、快递公司、付款人 openid。
 *
 * <h2>为什么是单独一个类</h2>
 * 台账只记「哪笔单、什么类型、报到第几次了」。真正要发的内容散在三张表上
 * （订单明细、子单、支付流水），而它们随时会变 —— <b>取值必须在发的那一刻做</b>，
 * 不能在入队时快照：入队发生在商家点发货那一瞬间，而那时运单号可能还在改
 * （{@code ship()} 允许改单号），快照下来的就是错的。
 *
 * <h2>缺件一律返回 null 并说清缺哪一件</h2>
 * 编一个空串发出去的话，微信回一个参数码，<b>而那个码不会告诉你缺的是哪一个字段</b>。
 * 这条规矩与 {@code WxShippingGateway.validate} 是同一条，只是这里能说得更具体
 * （它只看到 Command，看不到是哪张子单）。
 */
@Component
public class WxShippingUploadResolver {

    private final TradeMappers.SubOrderMapper subOrderMapper;
    private final TradeMappers.OrderItemMapper itemMapper;
    private final PaymentLedgerService ledger;

    public WxShippingUploadResolver(TradeMappers.SubOrderMapper subOrderMapper,
                                    TradeMappers.OrderItemMapper itemMapper,
                                    PaymentLedgerService ledger) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.ledger = ledger;
    }

    /**
     * @return 补全好的一次上报；<b>缺件返回 {@link Material#missing}</b>，
     *         调用方据此不发请求并把原因写进台账
     */
    public Material resolve(TrdShippingUpload row) {
        String orderNo = row.getOrderNo();

        var paid = ledger.paidPayment(orderNo);
        if (paid.isEmpty()) {
            return Material.missing("支付流水里没有这个订单付成功的那一笔");
        }
        String openid = paid.get().payerOpenid();
        if (openid == null || openid.isBlank()) {
            /*
             * V310 之前的流水没有这一列。报空串微信认不出这个人（而错误码只说参数错），
             * 所以宁可停在这里让人看见 —— 这类单要人工从微信支付后台查 openid 补。
             */
            return Material.missing("支付流水上没有付款人 openid（V310 之前的存量单）");
        }

        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getOrderNo, orderNo)));
        String desc = describe(items);
        if (desc.isBlank()) {
            return Material.missing("订单没有明细，凑不出商品描述（微信 10060008 不许为空）");
        }

        if (!WxLogisticsTypes.needsTracking(row.getLogisticsType())) {
            return new Material(desc, null, null, openid, null);
        }

        /*
         * 快递：运单号与快递公司**必须成对**（微信 268485226/227）。
         * 两者都在子单上（`express_no` / `express_company`，后者 V344 加的）。
         *
         * 缺任一就停住，**不编一个默认快递公司**：编了微信会收下
         * （它不校验公司与单号是否匹配），而买家点「查看物流」查到的是
         * 另一家的单号 —— 查无此单。存量单（V344 之前发的）没有公司，
         * 会停在这里，那是对的：它们本来就补不出来。
         */
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, orderNo)));
        OrdSubOrder shipped = subs.stream()
                .filter(x -> x.getExpressNo() != null && !x.getExpressNo().isBlank())
                .findFirst().orElse(null);
        if (shipped == null) {
            return Material.missing("快递单缺运单号");
        }
        String company = shipped.getExpressCompany();
        if (!ai.neargo.shop.common.ExpressCompanies.isValid(company)) {
            return Material.missing(company == null || company.isBlank()
                    ? "快递单缺快递公司（V344 之前发的存量单没有这一列，补不出来）"
                    : "快递公司编码认不得：" + company);
        }
        return new Material(desc, shipped.getExpressNo(), company, openid, null);
    }

    /** 商品描述。实现在 {@link ai.neargo.shop.common.GoodsDesc} —— 与支付的 description 同一串 */
    static String describe(List<OrdItem> items) {
        return ai.neargo.shop.common.GoodsDesc.of(
                items == null ? List.of() : items.stream().map(OrdItem::getTitle).toList(),
                ai.neargo.shop.common.GoodsDesc.SHIPPING_MAX);
    }

    /**
     * @param missReason 非 null 表示这一次发不出去，原文写进台账的 {@code err_msg}
     */
    public record Material(String itemDesc, String trackingNo, String expressCompany,
                           String payerOpenid, String missReason) {

        static Material missing(String reason) {
            return new Material(null, null, null, null, reason);
        }

        public boolean ready() {
            return missReason == null;
        }
    }
}
