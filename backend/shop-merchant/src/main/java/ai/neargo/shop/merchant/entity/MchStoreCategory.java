package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店经营类目：<b>这家店打算卖哪几类</b>（TDD-品类约束全链路 §3.1）。
 *
 * <p><b>挂门店不挂主体</b> —— 被资质约束的是门店。而商品仍然挂主体（ADR-011 不动），
 * 两者不矛盾，因为约束落在<b>「上架到门店」</b>那一步，不在建品那一步。
 * 一句话：<b>建品看主体，上架看门店。</b>
 *
 * <p>⚠️ 与 {@code prd_store_goods} / {@code prd_store_stock} 的语义<b>不同</b>：
 * 那两张是「<b>覆盖</b>」（有行才按店算，没行的店视为 0），这张是「<b>声明</b>」。
 * 写成覆盖语义的话，一个类目都没配的新店什么都上不了架 —— 而那正是新店的初始状态。
 *
 * <p>与 {@code mch_entity.category_codes} 也是两件事，别混：那是<b>平台批给主体的资质授权码</b>
 * （决定他<b>能不能</b>卖这类），这张是<b>商家自己选的经营类目</b>（决定他<b>店里怎么摆</b>）。
 * 一个是平台发的证，一个是商家的货架。
 */
@Getter
@Setter
@TableName("mch_store_category")
public class MchStoreCategory extends BaseEntity {

    private String storeNo;
    /** 冗余主体号，数据域锚点用。 */
    private String entityNo;
    /** 引用平台类目。 */
    private String categoryNo;

    /**
     * 自定义显示名；空 = 用平台类目名。
     *
     * <p>它只是<b>皮</b>：底下的 {@code category_no} 不变，商家把「蔬菜」叫成「今日现摘」，
     * 跨店聚合照常成立。自由命名的分组做不到这一点 —— 一家「蔬菜区」、一家「新鲜蔬菜」、
     * 一家「绿叶子」，聚合当场失效。
     */
    private String displayName;

    /** 店铺页里的展示顺序，商家拖动改的就是它。 */
    private Integer sort;

    private Boolean enabled;
}
