package ai.neargo.shop.inventory.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会被改的那十四张表的基类 —— 比 {@link InvEntity} 多两列 {@code updatedAt} / {@code updatedBy}。
 *
 * <p>分成两个基类不是为了少写两行，是**让「这张表能不能改」在类型上就能看出来**：
 * 继承 {@code InvEntity} 的实体没有 {@code setUpdatedAt}，
 * 于是「给流水写一次 update」这件事在编译期就不成立。
 */
@Getter
@Setter
public abstract class InvMutableEntity extends InvEntity {

    private LocalDateTime updatedAt;

    private String updatedBy;
}
