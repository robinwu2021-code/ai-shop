package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

/**
 * 平台端全量订单检索（P-4.1.1）。
 *
 * <p>与 C 端 {@link OrderService#list} / B 端 {@link MerchantOrderService#list} 是**三个方法**：
 * 过滤条件完全不同（属主 / 商家 / 无），合成一个再靠参数分支，
 * 就等于把「谁能看什么」交给调用方决定。
 */
public interface PlatformOrderService {

    PageData<OrderVO> search(String status, long page, long size);
}
