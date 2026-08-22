package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * SUBSET 收窄：某店某路只适用哪些范围项（P2 启用，P0 只建表）。
 *
 * <p>范围项仍是<b>主体级</b>（{@code mch_service_area}）——门店在主体申报的
 * 大范围里各自收窄。范围项被移除时 Service 同事务级联删各店引用行；
 * SUBSET 而引用集为空 = 这一路谁也送不了，保存时拦截。物理删除。
 */
@Getter
@Setter
@TableName("mch_channel_area")
public class MchChannelArea extends BaseEntity {

    private String storeNo;

    /** EXPRESS 不允许出现（快递天然全国，超区规则在运费模板里） */
    private String channel;

    private String areaNo;
}
