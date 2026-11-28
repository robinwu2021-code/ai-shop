package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.beauty.config.BeautyIndustryTables;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.config.IndustryTableMapper;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mysql.cj.jdbc.MysqlDataSource;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 美业的行业表建在按 {@code db/n_industry} V1、V2 新建的行业库上（TDD-美业域迁移新库 B-1）：
 * 行业句柄下的每个 Mapper 都登记在 {@link BeautyIndustryTables}，并能按实体全字段在新库上查询。
 * 需要本机 MySQL（{@code -Dbeauty.test.admin-url=jdbc:mysql://127.0.0.1:3306/mysql?...}）。
 */
class BeautyIndustryDatabaseIT {
    private MysqlDataSource admin;
    private String database;
    private JdbcTemplate industry;

    @BeforeEach
    void industryDatabase() throws IOException {
        String url = System.getProperty("beauty.test.admin-url");
        assertNotNull(url, "Set -Dbeauty.test.admin-url to a disposable local MySQL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"));
        admin = new MysqlDataSource();
        admin.setUrl(url);
        admin.setUser("root");
        admin.setPassword(System.getenv().getOrDefault("BEAUTY_NATIVE_MYSQL_PASSWORD", ""));
        database = "industry_" + UUID.randomUUID().toString().replace("-", "");
        // 与 .61 的 n_industry 一致：库的默认排序是 utf8mb4_0900_ai_ci
        new JdbcTemplate(admin).execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var source = new MysqlDataSource();
        source.setUrl(url.replace("/mysql?", "/" + database + "?"));
        source.setUser("root");
        source.setPassword(admin.getPassword());
        industry = new JdbcTemplate(source);
        for (String script : List.of("db/n_industry/V1__industry_design.sql", "db/n_industry/V2__beauty_supplement.sql"))
            for (String statement : statements(script)) industry.execute(statement);
    }

    @AfterEach
    void drop() {
        new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test
    void everyIndustryMapperIsRegisteredAndResolvesOnTheIndustryDatabase() {
        Map<String, Class<?>> mapped = new LinkedHashMap<>();
        for (Class<?> type : BeautyMappers.class.getDeclaredClasses()) {
            if (!IndustryTableMapper.class.isAssignableFrom(type)) continue;
            Class<?> entity = entity(type);
            mapped.put(entity.getAnnotation(TableName.class).value(), entity);
        }
        assertEquals(new TreeSet<>(BeautyIndustryTables.NAMES), new TreeSet<>(mapped.keySet()),
                "行业句柄下的 Mapper 与 BeautyIndustryTables 登记的表要一致");
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "beauty-industry");
        assistant.setCurrentNamespace("beauty-industry");
        List<String> failures = new ArrayList<>();
        mapped.forEach((table, entity) -> {
            var info = TableInfoHelper.initTableInfo(assistant, entity);
            try {
                industry.queryForList("SELECT " + info.getAllSqlSelect() + " FROM " + table + " LIMIT 1");
            } catch (RuntimeException failure) {
                failures.add(table + ": " + failure.getMessage());
            }
        });
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        // 设计稿去掉的触发器由 V2 补回；库存意向搬来后带着它的两个触发器（§2.6 第 11 项）
        assertEquals(List.of("trg_bsef_immutable", "trg_bsef_no_delete", "trg_bsesi_immutable", "trg_bsesi_no_delete",
                        "trg_bty_source_observation_domain_guard"),
                industry.queryForList("SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema=DATABASE()"
                        + " ORDER BY trigger_name", String.class));
    }

    private static Class<?> entity(Class<?> mapper) {
        for (var generic : mapper.getGenericInterfaces())
            if (generic instanceof ParameterizedType p && p.getRawType() == IndustryTableMapper.class)
                return (Class<?>) p.getActualTypeArguments()[0];
        throw new IllegalStateException("No entity type on " + mapper.getName());
    }

    /** 按 {@code ;} 切语句，识别 {@code DELIMITER} 块（触发器体里有分号）。 */
    static List<String> statements(String resource) throws IOException {
        String text = new String(new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        String delimiter = ";";
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (current.isEmpty() && (trimmed.isEmpty() || trimmed.startsWith("--"))) continue;
            current.append(line).append('\n');
            if (trimmed.endsWith(delimiter)) {
                String sql = current.toString().trim();
                out.add(sql.substring(0, sql.length() - delimiter.length()).trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) out.add(current.toString().trim());
        return out;
    }
}
