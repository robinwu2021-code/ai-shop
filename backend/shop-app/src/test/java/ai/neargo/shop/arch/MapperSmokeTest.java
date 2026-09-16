package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 每一个 MyBatis-Plus mapper 都能对它自己的表发一次真查询。
 *
 * <h2>为什么不是重复造 entity-alignment</h2>
 * <p>{@code packages/shared/tests/entity-alignment.test.ts} 比的是**实体 ↔ DDL 文本**，静态的。
 * 这一条是**运行时**：让 MyBatis 自己拼 SQL、真发到数据库、真解析结果集。
 * 两者抓的东西不一样 —— 静态那条看不见「这条 SQL 在这个数据库引擎上跑不跑得通」，
 * 也看不见「这个 mapper 实际落在哪个数据源」（三个库：ai_shop / ai_shop_inv / ai_shop_job，
 * 走三套 SqlSessionTemplate，接错了静态检查一个字都不会说）。
 *
 * <h2>为什么是 selectList(LIMIT 1) 而不是 selectCount</h2>
 * <p>{@code selectCount} 发的是 {@code SELECT COUNT(*) FROM t} —— 它只证明表在，
 * <b>一个列都没验到</b>。{@code selectList} 发的是 {@code SELECT <每一个映射列> FROM t LIMIT 1}，
 * 实体上多写一列、列名拼错、类型对不上，都会在这里报出来。
 * 空表也算数：SQL 仍然要被数据库解析、列仍然要存在。
 *
 * <h2>2026-09-16 加它的由来</h2>
 * <p>当天把生产从 MariaDB 切到 MySQL 9.7。切换本身逐表对过账，但**「应用发出去的每一条 SQL
 * 在新引擎上能不能跑」没有任何东西验过** —— 1884 条后端测试跑在 H2 上，H2 既不是 MariaDB
 * 也不是 MySQL。方言差异（排序规则、sql_mode、函数）只会在「那条查询真跑到时」才报错，
 * 可能几天后才撞上，而那时它看起来像一个毫不相干的业务缺陷。
 *
 * <h2>怎么指向真的 MySQL</h2>
 * <p>**走仓库里已有的 {@code e2e} profile**，别另造一套连真库的配置 ——
 * 那份的注释写着它存在的理由：「H2 全绿而真库出错的情况本轮出过三次」。
 * <pre>
 * mvn -o -pl shop-app -am test -Dtest=MapperSmokeTest -DSMOKE_PROFILE=e2e \
 *   -DSMOKE_DB_URL='jdbc:mysql://127.0.0.1:13307/ai_shop_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
 *   -DSHOP_DB_USER=… -DSHOP_DB_PASS=… \
 *   -Dshop.inventory.datasource.url='…/ai_shop_inv?…' \
 *   -Dshop.inventory.datasource.username=… -Dshop.inventory.datasource.password=… \
 *   -Dshop.inventory.flyway-locations=classpath:db/inventory
 * </pre>
 *
 * <p><b>最后那一行别漏。</b> {@code h2db} profile 把进销存的迁移换成了 H2 等价脚本
 * （{@code classpath:db/inventory-h2}，与生产的 {@code db/inventory} 是两份不同的文件）。
 * 对真库跑时不换回来，Flyway 会报「Migration checksum mismatch for version 1」——
 * 读起来像「生产那条迁移被人改过」，其实只是测试 classpath 上那一份的校验和。
 * 2026-09-16 第一次跑就被它绊住，查了几轮才认出来。
 *
 * <p>URL 走 {@code SMOKE_DB_URL} 而不是 {@code -Dspring.datasource.url}：
 * {@code @TestPropertySource} 的内联属性优先级**高于**系统属性，直接传后者盖不住它。
 * （e2e profile 把端口写死成 3306，而本机那个端口常被占用，所以也不能直接用它的 URL。）
 * 不传 {@code SMOKE_PROFILE} 就用 {@code h2db}，跟着日常全量一起跑，
 * 当「实体 ↔ 表漂移」的常设闸门。
 */
@SpringBootTest
@ActiveProfiles({"test", "${SMOKE_PROFILE:h2db}"})
@TestPropertySource(properties = {
        // 进销存域默认 **关**，而生产是**开**的 —— 不开的话它那一批 mapper 会绑到主数据源，
        // 于是它们去主库找 inv_* 表。H2 档里所有表在同一个库，看不出区别；
        // 指向真 MySQL 时 inv_* 在另一个库，接错了当场就露。
        // ⚠️ 打开它会同时启用进销存自己的 Flyway（硬编码、没有开关），
        // 对真库跑时如果本地迁移与库里的校验和对不上会起不来 —— 那时用 -Dshop.inventory.enabled=false
        // 单测主库那批，并把「这一半没覆盖到」说在明处，别假装全测了。
        // **必须有自己的 H2 库名。** 与别的测试共用一个内存库的话，第二个 Spring 上下文
        // 会把 schema-test.sql 再灌一遍 —— sys_industry 主键冲突，而症状是
        // 「单独跑绿、进全量红」且报错指向一个毫不相干的地方。这条第一次进全量就是这么红的。
        "spring.datasource.url=${SMOKE_DB_URL:jdbc:h2:mem:mappersmoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "shop.inventory.enabled=${SMOKE_INVENTORY:true}",
})
@DisplayName("架构 · 每个 mapper 都能查它自己的表")
class MapperSmokeTest {

    /**
     * 扫描面下界。**这个数字存在的唯一理由是：少扫了要报。**
     * 「找出违规」型的检查，扫描面塌了就会全绿 —— 而全绿看起来正是我们想要的样子。
     * 2026-09-16 实测扫到 **188 个 bean**（119 个接口声明，多数据源各自注册一份）。
     * 留一点余量取 170：低于它说明 mapper 没被扫进来，不是「问题变少了」。
     */
    private static final int MIN_MAPPERS = 170;

    @Autowired
    ApplicationContext ctx;

    @Test
    @DisplayName("★★★ 每个 mapper 对自己的表发一次 SELECT，一条都不许炸")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyMapperCanQueryItsOwnTable() {
        Map<String, BaseMapper> mappers = ctx.getBeansOfType(BaseMapper.class);

        assertThat(mappers.size())
                .as("扫到的 mapper 数。少于 %d 说明它们没被扫描进来 —— "
                        + "那时这条用例会「全绿」，而它一个 SQL 都没发", MIN_MAPPERS)
                .isGreaterThanOrEqualTo(MIN_MAPPERS);

        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, BaseMapper> e : mappers.entrySet()) {
            try {
                // LIMIT 1：只要一行就够，验的是 SQL 能不能跑、列在不在，不是数据内容
                e.getValue().selectList(new QueryWrapper<>().last("LIMIT 1"));
            } catch (Exception ex) {
                failed.add(e.getKey() + "  →  " + rootCause(ex));
            }
        }

        assertThat(failed)
                .as("这些 mapper 发不出基本查询（%d/%d 失败）", failed.size(), mappers.size())
                .isEmpty();
    }

    /** 最里层那句话才说明问题，外面裹着的 MyBatis / Spring 包装层没有信息量。 */
    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return c.getClass().getSimpleName() + ": " + (m == null ? "(无消息)" : m.split("\n")[0]);
    }
}
