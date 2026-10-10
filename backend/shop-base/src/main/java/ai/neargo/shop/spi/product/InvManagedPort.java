package ai.neargo.shop.spi.product;

import java.util.Collection;
import java.util.Set;

/**
 * 哪些 SKU 记库存（TDD-商品纳入进销存开关 §3）。
 *
 * <p>判据只在商品域算一次：单品设置 › 本主体的品类设置 › 平台默认（实物 / 生鲜记，服务 / 券 / 虚拟不记）。
 * 调用方（双写端口、进销存桥）<b>不要自己按类目模板再判一遍</b> —— 两份判据迟早分岔，
 * 分岔的症状是「设置里写着不记库存，下单却还在往进销存里记」，而且不报错。
 */
public interface InvManagedPort {

    /**
     * 这些 SKU 里记库存的那几个。
     *
     * <p><b>查不到的 SKU 算「记」</b>：与改版前的行为一致 —— 宁可多镜像一笔（进销存那边
     * 找不到物料会当作已处理），不能把一件该记的货漏在账外。
     */
    Set<String> managedSkus(Collection<String> skuNos);
}
