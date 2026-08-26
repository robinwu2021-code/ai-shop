package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 进销存的实体基类：**只有建的那一次**。
 *
 * <p><b>为什么不复用平台的 {@code BaseEntity}</b>：那一个带 {@code tenantNo} / {@code version} /
 * {@code deleted} 三列，而这套表**刻意三样都不要**（见「数据库表结构 §二」）——
 * {@code ownerId} 就是隔离维度，软删一律用 {@code status}，流水根本不能删。
 * 继承过来的话 17 张表会凭空多出三列，且 {@code @TableLogic} 会给每条查询自动加
 * {@code deleted = 0}；库里没有这一列，报的是「未知列」，
 * 排查方向会指向 SQL 写错，不会指向基类。
 *
 * <p><b>继承关系即是不可变规矩</b>：只追加 / 写一次的三张表（{@code inv_ledger} ·
 * {@code inv_item_ref} · {@code inv_reservation_line}）直接继承本类，
 * <b>因此它们的实体上根本没有 {@code updatedAt} 这个字段</b> ——
 * 不变式 I3 由类型兜住，而不是靠代码里记得别写 update。
 * 其余十四张继承 {@link InvMutableEntity}。
 *
 * <p><b>审计列不自动填</b>：平台那套 {@code @TableField(fill = ...)} 依赖注册在平台
 * SqlSessionFactory 上的 MetaObjectHandler，而本领域的工厂刻意不装任何插件。
 * {@code createdAt} 由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP} 兜底；
 * <b>{@code createdBy} 由 Service 显式写</b> —— 「谁改的」是这套东西的领域数据，
 * 不是顺带记的审计。
 */
@Getter
@Setter
public abstract class InvEntity {

    /** 库内自增物理主键。**不对外、不进 URL** —— 对外一律用业务键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDateTime createdAt;

    /** 谁建的。业务键：商家账号 / 运营账号 / {@code SYSTEM}。 */
    private String createdBy;
}
