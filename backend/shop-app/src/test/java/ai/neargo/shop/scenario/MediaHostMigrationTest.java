package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.media.MediaRefColumn;
import ai.neargo.shop.media.MediaRefSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V347：图片地址从 COS 公网域名迁到规范地址 img.hxmall.top（ADR-026，TDD-图片走服务器与流量切换 T5）。
 *
 * <p>两件事分开证：
 * <ol>
 *   <li><b>覆盖面</b>：迁移里的字段 = 回收扫描登记表的字段，一个不多一个不少。
 *       将来加了图片列而这份迁移没覆盖到，那一列里的 COS 地址会一直直连 COS —— 流量费照旧，而且不报错；</li>
 *   <li><b>替换语义</b>：单值、JSON 数组、富文本里嵌着的都换，相似域名不动。</li>
 * </ol>
 *
 * <p>H2 与 MySQL 在 JSON 列上的行为不同，这条测试只证 H2 上的语义；
 * MySQL 9.7 上另在本机副本库跑过一次（见 TDD §五 T5 的偏差说明）。
 *
 * <p><b>不留下共享种子</b>：改动的每一行在 finally 里还原（仓库里吃过「单独跑绿、全量红」的亏）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("V347 图片地址迁到 img.hxmall.top")
class MediaHostMigrationTest {

    private static final String OLD = "https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/";
    private static final String NEW = "https://img.hxmall.top/";
    private static final Pattern UPDATE = Pattern.compile(
            "UPDATE (\\w+) SET (\\w+) = REPLACE\\(\\2, '([^']+)', '([^']+)'\\) WHERE \\2 LIKE '%\\3%';");

    /** 主迁移 + 各条补充。新增图片列时在这里加上它那条 —— 漏了的话覆盖面断言当场变红 */
    private static final List<String> MIGRATIONS = List.of(
            "V347__media_host_img.sql",
            "V369__media_host_store_banner.sql");

    @Autowired ApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    /** 迁移文件里的每条 UPDATE：「表.列」→ 那条 SQL。 */
    private static Map<String, String> statements() throws Exception {
        /*
         * V347 是主迁移；**后来新增的图片列各带一条补充迁移** —— V347 已应用，
         * 回去改它 checksum 就对不上，线上起不来。这里把两者合起来看，
         * 「登记表的每一列都换过域名」这条判据才不会因为文件拆开而漏掉新列。
         */
        StringBuilder sb = new StringBuilder();
        for (String f : MIGRATIONS) {
            sb.append(new ClassPathResource("db/migration/" + f).getContentAsString(StandardCharsets.UTF_8))
                    .append('\n');
        }
        String sql = sb.toString();
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = UPDATE.matcher(sql);
        while (m.find()) {
            assertThat(m.group(3)).as("旧前缀").isEqualTo(OLD);
            assertThat(m.group(4)).as("新前缀").isEqualTo(NEW);
            out.put(m.group(1) + "." + m.group(2), m.group());
        }
        return out;
    }

    private List<MediaRefColumn> registry() {
        List<MediaRefColumn> all = new ArrayList<>();
        ctx.getBeansOfType(MediaRefSource.class).values().forEach(s -> all.addAll(s.columns()));
        return all;
    }

    @Test
    @DisplayName("★★★ 覆盖面：迁移的字段 = 回收扫描登记表的字段 —— 漏一列，那一列就一直直连 COS")
    void coversExactlyTheRegistry() throws Exception {
        Set<String> registered = new TreeSet<>();
        registry().forEach(c -> registered.add(c.table() + "." + c.column()));
        Set<String> migrated = new TreeSet<>(statements().keySet());

        assertThat(registered).as("登记表本身不能是空的 —— 空集会让下面这条断言恒真").hasSizeGreaterThan(20);
        assertThat(migrated).isEqualTo(registered);
    }

    @Test
    @DisplayName("★★★ 替换语义：单值、JSON 数组、富文本都换，相似域名不动；每一行用完还原")
    void replacesEveryForm() throws Exception {
        Map<String, String> sqlByColumn = statements();
        String value = "[\"" + OLD + "a/b.jpg\",\"" + OLD + "c/d.png\"]";
        String expected = "[\"" + NEW + "a/b.jpg\",\"" + NEW + "c/d.png\"]";
        String untouched = "[\"https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.comx/e.jpg\"]";
        List<String> exercised = new ArrayList<>();

        for (MediaRefColumn c : registry()) {
            /*
             * keyColumn 不保证只对应一行：ord_item 一个 sub_order_no 有多件。
             * 直接 LIMIT 1 再按它去 queryForObject，碰上「这一单有两件」就抛 IncorrectResultSize，
             * 而这取决于本测试之前哪条用例往同一张 H2 库里插过多件单 —— 表现成与本测试无关的随机串台
             * （报表用例落多件单后，全量里它恰好排在前面就红）。所以挑一个只对应一行的键。
             * 都对应多行时跳过该列（与这张表为空时同样处理），下面的 exercised ≥ 5 仍兜得住。
             */
            List<Object> keys = jdbc.queryForList(
                    "SELECT " + c.keyColumn() + " FROM " + c.table()
                            + " GROUP BY " + c.keyColumn() + " HAVING COUNT(*) = 1"
                            + " ORDER BY " + c.keyColumn() + " LIMIT 1", Object.class);
            if (keys.isEmpty()) {
                continue;
            }
            Object key = keys.get(0);
            String where = " WHERE " + c.keyColumn() + " = ?";
            String original = jdbc.queryForObject(
                    "SELECT CAST(" + c.column() + " AS VARCHAR) FROM " + c.table() + where, String.class, key);
            try {
                jdbc.update("UPDATE " + c.table() + " SET " + c.column() + " = ?" + where, value, key);
                jdbc.execute(sqlByColumn.get(c.table() + "." + c.column()));
                assertThat(jdbc.queryForObject("SELECT CAST(" + c.column() + " AS VARCHAR) FROM "
                        + c.table() + where, String.class, key))
                        .as("%s.%s", c.table(), c.column()).isEqualTo(expected);

                jdbc.update("UPDATE " + c.table() + " SET " + c.column() + " = ?" + where, untouched, key);
                jdbc.execute(sqlByColumn.get(c.table() + "." + c.column()));
                assertThat(jdbc.queryForObject("SELECT CAST(" + c.column() + " AS VARCHAR) FROM "
                        + c.table() + where, String.class, key))
                        .as("%s.%s：只差一个字符的别家域名不能被换", c.table(), c.column()).isEqualTo(untouched);
                exercised.add(c.table() + "." + c.column());
            } finally {
                jdbc.update("UPDATE " + c.table() + " SET " + c.column() + " = ?" + where, original, key);
            }
        }
        assertThat(exercised).as("至少要真跑到几列，否则这条测试什么都没证（跑到的：%s）", exercised)
                .hasSizeGreaterThanOrEqualTo(5);
    }
}
