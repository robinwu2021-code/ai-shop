package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.CartItemVO;

import java.util.List;

/** 服务端购物车（[API 清单 §2.4]）。全部需要登录 —— 游客加购在端上拦截。 */
public interface CartService {

    List<CartItemVO> list();

    List<CartItemVO> add(String goodsNo, String skuNo, int qty);

    /** qty <= 0 视为移除，端上「减到 0」不必再调另一个接口。 */
    List<CartItemVO> update(String skuNo, int qty);

    List<CartItemVO> remove(List<String> skuNos);
}
