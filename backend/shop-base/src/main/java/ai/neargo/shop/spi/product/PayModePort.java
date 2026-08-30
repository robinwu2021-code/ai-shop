package ai.neargo.shop.spi.product;

import java.util.Set;

/**
 * 「这件商品在这家店支持哪些支付方式」——给 <b>trade 域</b>用的出口。
 *
 * <p>为什么要有这个 Port：下单链路要判支付方式，而判据全在 product 域
 * （类目 × 资质 × 门店 × 商品四层取交集）。此前 {@code OrderServiceImpl}
 * <b>直接注入了 {@code product.service.PayModeService}</b>，两个域就此长在一起 ——
 * 而 ArchitectureTest 那条规则常年红着，于是这次跨域依赖<b>没有任何信号</b>地混了进来。
 *
 * <p>能力只开这一条，不是把 PayModeService 整个搬过来：Port 是<b>跨域契约</b>，
 * 它的受众和本域 Service 的受众看到的范围本来就该不一样。
 */
public interface PayModePort {

    /**
     * 这件商品在这家店支持哪些支付方式。<b>永远至少包含 {@code ONLINE}</b> ——
     * 线上支付不受那四层约束，否则配错一处就会出现「这件商品谁也买不了」。
     *
     * @param storeNo 可空。空表示按主体判（单店场景两者恒等）
     */
    Set<String> availablePayModes(String goodsNo, String storeNo);
}
