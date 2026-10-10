package ai.neargo.shop.product.dto;

import java.util.List;

/** 类目节点（对齐 c-app 分类页）。叶子节点 {@code children} 为空列表而不是 null —— 端上少一次判空。 */
public record CategoryVO(String categoryNo,
                         String parentNo,
                         int level,
                         String name,
                         String icon,
                         int sort,
                         /**
                          * 该类目对应的**品类模板**：STANDARD / FRESH / SERVICE / VOUCHER。
                          *
                          * <p><b>它就是「品类」，只是换了一套码</b>（STANDARD↔NORMAL、VOUCHER↔CARD）。
                          * 此前没往端上传，于是商家要把同一件事填两遍 —— 先选品类，再选类目 ——
                          * 而两者**可以互相矛盾**：选「生鲜」品类配「纸品清洁」类目，没有任何一处会拦。
                          * 而品类决定履约与合规（生鲜要截单、服务不发货），选错要到下单时才显现。
                          */
                         String template,
                         /**
                          * 经营这个类目要的授权码；<b>空 = 无门槛</b>。
                          *
                          * <p>此前不下发，于是商家<b>选之前看不见门槛</b> ——
                          * 摆货架或上架时才撞 70002，而那句「你还没有资质授权」
                          * 既说不出缺哪张证，也说不出去哪申请。
                          */
                         String requiredCode,
                         /**
                          * 人读的资质名，如「食品经营许可证」。展示用，<b>不是校验依据</b>
                          * （判据是 {@link #requiredCode}）—— 但商家要看的恰恰是这一句。
                          */
                         List<String> qualifications,
                         List<CategoryVO> children) {
}
