package ai.neargo.shop.product.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.service.CategoryService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises the category editor and the label resolution used when publishing on the split schema. */
@EnabledIfEnvironmentVariable(named = "C06_REVIEW_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class SplitSpecTemplateReadsMysqlTest {
    @Test
    void editorAndBakeResolveScopedOptionsFromSeparateUsageTables() throws Exception {
        String schema = "spec_template_test_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:mysql://127.0.0.1:3308/";
        String user = System.getenv("C06_REVIEW_MYSQL_USER");
        String password = System.getenv("C06_REVIEW_MYSQL_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url + "information_schema", user, password));
        String ddl = new ClassPathResource("db/product-split-spec/verified-spec-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(url + schema, user, password);
            var jdbc = new JdbcTemplate(source);
            for (String table : List.of("prd_spec_dim", "prd_spec_value", "prd_spec_usage", "prd_spec_value_usage")) {
                var match = Pattern.compile("(?ms)^(CREATE TABLE `" + table + "` .*?);$")
                        .matcher(ddl);
                assertThat(match.find()).as(table).isTrue();
                jdbc.execute(match.group(1).trim());
            }
            jdbc.update("""
                    INSERT INTO prd_spec_dim (tenant_no, dim_no, name, scope, status, usage_type, value_type)
                    VALUES ('MAIN','SIZE','包装','PLATFORM','ACTIVE','SALE','ENUM')
                    """);
            jdbc.update("""
                    INSERT INTO prd_spec_value (tenant_no, dim_no, value_no, code, label, scope, status, sort) VALUES
                    ('MAIN','SIZE','VALUE_L','L','大','PLATFORM','ACTIVE',2),
                    ('MAIN','SIZE','VALUE_S','S','小','PLATFORM','ACTIVE',1)
                    """);
            jdbc.update("""
                    INSERT INTO prd_spec_usage (tenant_no,scope_type,scope_ref,dim_no,status,is_primary) VALUES
                    ('MAIN','CATEGORY','CAT','SIZE','ACTIVE',1)
                    """);
            jdbc.update("""
                    INSERT INTO prd_spec_value_usage
                      (tenant_no,category_no,entity_no,dim_no,value_no,label_override,enabled) VALUES
                    ('MAIN','CAT',NULL,'SIZE','VALUE_L','大盒',1),
                    ('MAIN','CAT',NULL,'SIZE','VALUE_S',NULL,0),
                    ('MAIN',NULL,'MYSHOP','SIZE','VALUE_L','通用盒',1),
                    ('MAIN','CAT','MYSHOP','SIZE','VALUE_L','家庭装',1),
                    ('MAIN','CAT','OTHER','SIZE','VALUE_L','别家的盒',1)
                    """);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            var sessions = factory.getObject();
            sessions.getConfiguration().addMapper(SpecDimMapper.class);
            sessions.getConfiguration().addMapper(SpecValueMapper.class);
            var template = new SqlSessionTemplate(sessions);
            var dims = template.getMapper(SpecDimMapper.class);
            var values = template.getMapper(SpecValueMapper.class);
            var categories = mock(CategoryService.class);
            when(categories.categoryTypeOf("CAT")).thenReturn("NORMAL");
            var library = new SpecLibraryServiceImpl(dims, values, null, categories, null, null, null, null);
            var reader = new SplitSpecTemplateReads(source);
            ReflectionTestUtils.setField(library, "splitReads", reader);
            var row = library.templatesForCategory("MYSHOP", "CAT").getFirst();
            assertThat(row.templateNo()).isEqualTo("SIZE");
            assertThat(row.name()).isEqualTo("包装");
            assertThat(row.options()).containsExactly(new SpecTemplateVO.Option("L", "家庭装"));
            assertThat(library.resolveValueNos("MYSHOP", "SIZE", List.of("家庭装", "别家的盒")))
                    .containsOnly(java.util.Map.entry("家庭装", "VALUE_L"));
            var goods = mock(MerchantGoodsServiceImpl.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(goods, "specLibrary", library);
            var baked = goods.bakeSpecs("MYSHOP", "CAT", """
                    [{"name":"旧名","templateNo":"SIZE","options":["旧档位"],"optionCodes":["L"]}]
                    """);
            assertThat(baked.unresolved()).isEmpty();
            assertThat(baked.specGroupsJson()).contains("包装", "家庭装");
            assertThat(baked.valueNoByGroup().getFirst()).containsOnly(java.util.Map.entry("家庭装", "VALUE_L"));
            var book = new SpecTemplateBook(null, dims, values, library);
            ReflectionTestUtils.setField(book, "splitReads", reader);
            assertThat(book.favorites("MYSHOP")).isEmpty();
            assertThat(book.recommended("NORMAL")).isEmpty();
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }
}
