package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 自提路 × 取货点（P1 启用，P0 只建表）。
 *
 * <p>本店地址<b>刻意不落行</b>：门店地址天然是取货地址，落一行就要在门店改地址时
 * 同步两处——同一事实存两份是漂移的起点。读侧由 {@code mch_store.address} 合成。
 *
 * <p>点被运营停用后<b>不删本行，读侧过滤</b>——点恢复后配置原样回来。物理删除。
 */
@Getter
@Setter
@TableName("mch_channel_pickup")
public class MchChannelPickup extends BaseEntity {

    private String storeNo;

    /** 只允许 STORE_PICKUP / NEIGHBOR_PICKUP */
    private String channel;

    private String pickupNo;
}
