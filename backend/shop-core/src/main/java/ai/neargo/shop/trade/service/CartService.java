package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.CartItemVO;

import java.util.List;

/** 服务端购物车（[API 清单 §2.4]）。全部需要登录 —— 游客加购在端上拦截。 */
public interface CartService {

    List<CartItemVO> list();

    List<CartItemVO> add(String goodsNo, String skuNo, int qty);

    /**
     * 加购，**带买家正在逛的那家店**（TDD-C端商品归属门店与库存校验 AC6）。
     *
     * <p>门店只用于**这一刻的库存校验**，不落库 —— `trd_cart_item` 上没有 store_no。
     * 下单时仍由 `OrderServiceImpl` 自行落店（它判「在架 ∧ 有货」），
     * 两处的答案因此可能不同：在 B 店加的购，最后可能由 B 店发（多数情况）
     * 也可能因为 B 店此刻卖光而拒。这是可以接受的：加购只是提前告诉你，不占库存。
     *
     * @param storeNo 空 = 没有门店上下文，与 {@link #add(String, String, int)} 等价
     */
    List<CartItemVO> add(String goodsNo, String skuNo, int qty, String storeNo);

    /** qty <= 0 视为移除，端上「减到 0」不必再调另一个接口。 */
    List<CartItemVO> update(String skuNo, int qty);

    List<CartItemVO> remove(List<String> skuNos);
}
