package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店级上架关系：这家店卖不卖这件货。
 *
 * <p><b>与 {@link PrdStoreSku} 逐字同一套语义</b>：
 * <ul>
 *   <li>某商品一条店级行都没有 → 走 {@code prd_goods.on_sale}，行为与单店时代相同
 *   <li>一旦有了任意一条 → 该商品整体转为店级管理，<b>没有行的店视为未上架</b>
 * </ul>
 *
 * <p>两处语义必须一致。不一致的症状很难查：库存说这家店有货、上架说这家店没这商品，
 * 页面显示与下单结果各说各话。
 *
 * <p><b>没有分店价</b>：商品定义（含价格）仍在主体级。价格动的是算价链路，
 * 与「卖不卖」不是一个风险等级 —— 要单独一批做，而不是先建个列放着。
 */
@Getter
@Setter
@TableName("prd_catalog_item")
public class PrdStoreGoods extends BaseEntity {
    /**
     * 门店级上下架行的目录版本号：空串（门店交易库批次 2-g，原 {@code prd_store_goods} 并入 {@code prd_catalog_item}）。
     *
     * <p>同一张表里挂目录版本的是菜单条目（餐饮），不是这里的门店上下架；读写一律从
     * {@code StoreGoodsMapper.storeLevel()} 起手，只碰空串那一类。存空串不存 NULL：唯一键
     * {@code (tenant_no, store_no, goods_no, catalog_version_no)} 里 NULL 互不相等，挡不住重复。
     */
    public static final String STORE_LEVEL = "";

    private String catalogVersionNo = STORE_LEVEL;

    /** 新表的 tenant_no 没有默认值：不填就是 NULL，按租户查的地方（门店换绑检查）会漏掉这一行 */
    public PrdStoreGoods() {
        setTenantNo("MAIN");
    }

    private String storeNo;
    private String goodsNo;
    /** 冗余主体号：按商家查全部门店上架情况时免 join */
    private String entityNo;
    /** 新表叫 enabled（本目录里卖不卖这件商品），语义同旧的 on_sale */
    @com.baomidou.mybatisplus.annotation.TableField("enabled")
    private Boolean onSale;

    /**
     * 该行由平台强制下线压下（V96）。解除处置时**只恢复带标记的行**——
     * 商家在处置期间自己下架的东西不带标记，恢复时不动它，
     * 否则解除等于平台替商家做了一次全店上架。
     */
    private Boolean platformSuspended;
}
