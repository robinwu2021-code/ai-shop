package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

/**
 * B 端商家订单（[API 清单 §3.4]）。
 *
 * <p>与 C 端 {@link OrderService#list} 同粒度（子单），双方谈同一个订单号时不会各说各的。
 * 但**是两个方法而不是加个参数**：C 端按 userNo 过滤、B 端按 merchantNo 过滤，
 * 合成一个方法就要在里面判「现在是谁在调」—— 那正是越权最容易钻的缝。
 */
public interface MerchantOrderService {

    PageData<OrderVO> list(String merchantNo, String status, long page, long size);
}
