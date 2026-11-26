package ai.neargo.shop.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 标记：这个 Mapper 的表在支付商户库（ADR-066 的 payMerchant 模块）。
 *
 * <p>支付域的 Mapper 同在 {@code ai.neargo.shop.pay.mapper} 之下，新设计把它们分到支付商户库、支付库、
 * 账务结算库。支付域的整包扫描按这个类型排除，支付商户模块的扫描按这个类型收取。
 */
public interface PayMerchantTableMapper<T> extends BaseMapper<T> {
}
