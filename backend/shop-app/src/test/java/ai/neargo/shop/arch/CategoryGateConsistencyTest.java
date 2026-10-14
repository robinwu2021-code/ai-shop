package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营门槛的自洽性 —— 守的是**「挂了门槛，却没有能通过它的路」**这一类故障。
 *
 * <p>它不报错、不留日志，只表现成商家那句「你还没有资质授权」，
 * 而去哪申请没人说得出。一个只会拒绝的校验比没有校验更糟：它看起来在工作。
 *
 * <p>两条都在 2026-08-21 的线上库里真的踩到过：
 * <ul>
 *   <li>V168 归档三级类目时漏了 {@code PACKAGED_FOOD} —— 门槛<b>凭空消失</b>，
 *       任何人都能卖预包装食品（少一个门槛比多一个更难发现，没人会来投诉「怎么让我卖了」）</li>
 *   <li>CAT140 熟食卤味 → {@code FOOD}、CAT240 医药健康 → {@code DRUG_RETAIL}，
 *       而这两个码 {@code enabled=0}：授权接口只从启用码里挑，
 *       运营**授不出去**，于是这两类<b>永远拒绝所有人</b></li>
 * </ul>
 *
 * <p>跑在 H2 测试库上，读的是同一批 Flyway 迁移落下来的种子 ——
 * 所以下一次有人加类目、停用码时，这里当场变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class CategoryGateConsistencyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★ 在架类目引用的授权码必须存在且启用 —— 否则那个类目永远拒绝所有人")
    void activeCategoriesOnlyReferenceEnabledCodes() {
        List<String> broken = jdbc.queryForList("""
                SELECT CONCAT(c.category_no, ' ', c.name, ' → ', c.required_code,
                              CASE WHEN a.code IS NULL THEN '（码不存在）' ELSE '（码已停用）' END)
                FROM prd_category c
                LEFT JOIN sys_auth_code a ON a.code = c.required_code AND a.deleted = 0
                WHERE c.deleted = 0 AND c.status = 'ACTIVE'
                  AND c.required_code IS NOT NULL AND c.required_code <> ''
                  AND (a.code IS NULL OR a.enabled = 0)
                """, String.class);
        assertThat(broken)
                .as("""
                        这些在架类目挂着一个**发不出来**的码：商家看到「你还没有资质授权」，
                        而运营在授权页里根本挑不到这个码 —— 两边都动不了。
                        修法二选一：把码启用（保留门槛），或把类目归档（这一期不做）。
                        千万别把类目上的 required_code 摘掉 —— 那是把「谁都卖不了」
                        换成「谁都能卖」，后者更贵。""")
                .isEmpty();
    }

    /**
     * 启用着、却<b>有意</b>不挂在任何类目上的码。
     *
     * <p>不是所有码都用来把人挡在门外：
     * <ul>
     *   <li>{@code DAILY} —— 进件时声明「我卖日用百货」用的，日用类目本身无门槛。
     *       把它挂到 CAT200 上会让**现有的所有日用商家当场失去上架能力**，
     *       那是拿一个更大的错去换一个不存在的错</li>
     *   <li>{@code FOOD} —— 熟食卤味类目是运营在界面上建的（生产库有，迁移种子里没有）。
     *       类目本来就是运营可维护的数据，种子测不到它，这条不该因此变红</li>
     * </ul>
     */
    private static final List<String> UNGATING_CODES = List.of("DAILY", "FOOD");

    @Test
    @DisplayName("★ 启用中的授权码都要有类目在用 —— 发得出证却没有门要开，说明归档时漏了上移")
    void enabledCodesAreReferencedBySomeActiveCategory() {
        List<String> orphan = jdbc.queryForList("""
                SELECT CONCAT(a.code, ' ', a.name)
                FROM sys_auth_code a
                WHERE a.deleted = 0 AND a.enabled = 1
                  AND NOT EXISTS (
                      SELECT 1 FROM prd_category c
                      WHERE c.deleted = 0 AND c.status = 'ACTIVE'
                        AND c.required_code = a.code)
                """, String.class);
        orphan = orphan.stream()
                .filter(row -> UNGATING_CODES.stream().noneMatch(c -> row.startsWith(c + " ")))
                .toList();
        assertThat(orphan)
                .as("""
                        这些码启用着，却没有任何在架类目引用它 —— 运营能授、商家能拿，
                        但它开不了任何一道门。V168 归档三级类目时漏掉 PACKAGED_FOOD 的
                        上移，症状就是这一条：**门槛凭空消失**，而没有任何报错。
                        要么把码挂回对应的二级类目，要么停用它。""")
                .isEmpty();
    }
}
