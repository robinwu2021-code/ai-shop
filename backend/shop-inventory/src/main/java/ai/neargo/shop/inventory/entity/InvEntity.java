package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * <p><b>时间列由本域自己的填充器写</b>（{@code InventoryDataSourceConfig} 里那个
 * MetaObjectHandler）。刻意不装的是**拦截器**（DataScope / 分页 / 乐观锁）——
 * 填充器不是拦截器，它不改 SQL 语义，只是把两个时间戳补上。
 * 不装它的话 MyBatis-Plus 会把 null 显式写进 INSERT，
 * 把 DDL 上的 {@code DEFAULT CURRENT_TIMESTAMP} 顶掉，报的是「created_at 不能为空」。
 *
 * <p><b>{@code createdBy} 仍然由 Service 显式写</b> —— 「谁改的」是这套东西的领域数据，
 * 不是顺带记的审计，不该由一个通用填充器猜。
 */
@Getter
@Setter
public abstract class InvEntity {

    /** 库内自增物理主键。**不对外、不进 URL** —— 对外一律用业务键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 谁建的。业务键：商家账号 / 运营账号 / {@code SYSTEM}。 */
    private String createdBy;
}
