package ai.neargo.shop.common;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计列的自动填充。**这一条守的是一个沉默了很久的缺陷**：
 *
 * <p>原实现用 {@code strictUpdateFill}，而它只在字段为 null 时才填。
 * 更新路径全是「查出实体 → 改字段 → updateById」，查出来的实体上
 * {@code updatedAt} 一定有值，于是这两列从插入之后再也没变过 ——
 * 库里 507 行、version 最高到 5，没有一行的 updated_at 与 created_at 不同。
 *
 * <p>它不会报错、不会为空、看着完全正常，所以只能靠一条测试守住。
 */
class AuditMetaObjectHandlerTest {

    /** 最小实体：只要有这两个字段 + setter，就够走填充逻辑 */
    static class Row {
        private LocalDateTime updatedAt;
        private String updatedBy;

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }
    }

    @Test
    @DisplayName("★★★ 已有值也要覆盖 —— 从库里查出来的实体上这两列必然有值，不覆盖就等于永不更新")
    void updateFillOverwritesExistingValues() {
        Row row = new Row();
        LocalDateTime insertedAt = LocalDateTime.of(2020, 1, 1, 0, 0);
        row.setUpdatedAt(insertedAt);
        row.setUpdatedBy("原来那个人");

        MetaObject meta = SystemMetaObject.forObject(row);
        new AuditMetaObjectHandler().updateFill(meta);

        assertThat(row.getUpdatedAt())
                .as("只在 null 时填的话，这一列永远停在插入时间 —— "
                        + "而界面上它被当作「最后一次变更时间」在显示")
                .isAfter(insertedAt);
        assertThat(row.getUpdatedBy())
                .as("停在申请人身上的话，「这笔退款是谁同意的」在数据里查不到")
                .isEqualTo("SYSTEM");   // 测试里没有登录态
    }

    @Test
    @DisplayName("★★ 没有这两个字段的实体不能炸 —— 并非所有表都继承 BaseEntity")
    void skipsEntitiesWithoutAuditColumns() {
        Object plain = new Object() {
            @SuppressWarnings("unused")
            public String getName() {
                return "x";
            }
        };
        MetaObject meta = SystemMetaObject.forObject(plain);
        assertThatCode(() -> new AuditMetaObjectHandler().updateFill(meta))
                .doesNotThrowAnyException();
    }
}
