package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店销售目录的一个版本（门店交易库 {@code prd_store_catalog}，TDD-商品领域迁到门店交易库 批次 2-g）。
 *
 * <p>旧库是「头 + 版本」两张表（{@code prd_store_catalog} 记当前版本号，{@code prd_store_catalog_version}
 * 存每一版的冻结内容）。门店交易库并成一张：<b>一个版本一行</b>，唯一约束
 * {@code (tenant_no, entity_no, catalog_no, version_no)} 就是发布的并发闸门 —— 两个人基于同一版发布，
 * 后插的那一个撞键。
 *
 * <p>多版本并存是为了<b>定时生效</b>：先发一版明天生效的，今天照卖旧的。读与结账都取「已生效里最新的那一版」，
 * 所以不能只存一行。
 *
 * <p>{@code frozen_content} 是发布时的原文，快照摘要按它算 —— 列类型不能用 MySQL 的 json（会被规范化，读回即对不上）。
 */
@Getter
@Setter
@TableName("prd_store_catalog")
public class PrdStoreCatalog extends BaseEntity {
    /** 已发布。目录发布即定稿，没有草稿阶段 */
    public static final String STAGE_PUBLISHED = "PUBLISHED";

    private String entityNo;
    private String storeNo;
    /**
     * 目录编号。门店销售目录一店一份，新建时就用门店号 —— 两个人同时首发时撞唯一键，
     * 而不是各自生成一个编号、留下两份目录。旧库迁来的目录保留原编号。
     */
    private String catalogNo;
    /** 这一版的编号（对外与订单冻结用） */
    private String catalogVersionNo;
    private String stage;
    /** 同一目录内自增的版本号，从 1 起：发布时带的 expectedRevision 就是它 */
    private Integer versionNo;
    private LocalDateTime publishedAt;
    private LocalDateTime effectiveAt;
    private String frozenContent;
    private String snapshotHash;
}
