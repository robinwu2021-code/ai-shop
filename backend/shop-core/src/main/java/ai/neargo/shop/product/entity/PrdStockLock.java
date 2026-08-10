package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库存锁定明细。
 *
 * <p>没有它，「释放」就只能靠调用方把 SKU 和数量再传一遍 —— 而超时释放任务手上只有订单号。
 * 有了它，释放与确认都只需要 {@code lockNo}，且**天然幂等**（只处理 LOCKED 状态的行）：
 * 用户取消与超时任务同时触发时，不会把库存加两次。
 */
@Getter
@Setter
@TableName("prd_stock_lock")
public class PrdStockLock extends BaseEntity {

    public static final String LOCKED = "LOCKED";
    public static final String RELEASED = "RELEASED";
    public static final String CONFIRMED = "CONFIRMED";

    /** 锁定单号 = 订单号。 */
    private String lockNo;

    private String skuNo;
    private Integer qty;

    /**
     * 锁的是哪家店的库存。**空 = 主体级**（存量锁定行，或该 SKU 还没启用分店库存）。
     * 释放与确认要靠它决定把数减回哪张表 —— 减错表的后果是库存凭空多出或少掉一批。
     */
    private String storeNo;

    /** LOCKED / RELEASED / CONFIRMED */
    private String status;

    private LocalDateTime lockedAt;
    private LocalDateTime settledAt;
}
