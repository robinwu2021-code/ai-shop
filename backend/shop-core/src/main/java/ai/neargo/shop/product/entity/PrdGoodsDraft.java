package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品草稿：一件商品至多一份未发布修改（TDD-商品规格与发布 §3.3）。
 *
 * <p>线上版是 {@code prd_goods}（C 端只读它，照常卖）；这张表是 B 端的编辑缓冲。
 * 「有未发布修改」标识的判据就是**这张表有没有行** —— 所以保存的内容与线上
 * 相同时要删行（防假标识），发布成功也删行。
 *
 * <p>⚠️ 删行走 {@code purge}（物理删）：唯一键 {@code uk_goods_draft(goods_no)}
 * 不含 deleted，逻辑删会挡住同一商品再建草稿 —— V195 覆盖表踩过的同一个坑。
 */
@Getter
@Setter
@TableName("prd_goods_draft")
public class PrdGoodsDraft extends BaseEntity {

    /** 编辑中 —— 只有商家自己看得到 */
    public static final String EDITING = "EDITING";
    /** 已提交待审（审核开关开着时）。线上旧版**继续卖** —— 这正是双版本的意义 */
    public static final String SUBMITTED = "SUBMITTED";

    private String goodsNo;
    private String entityNo;
    /** 整份 SaveCommand 的 JSON。编辑缓冲非契约，正确性由发布编译点保证 */
    private String payload;
    /**
     * 草稿基于哪一版线上（prd_goods.version，乐观锁列）。发布时对不上 = 有人中途
     * 改过线上，拒。**不用 updated_at**：秒级精度，同秒改动检测不到（combo 测试撞出的）。
     */
    private Long baseVersion;
    private String status;
}
