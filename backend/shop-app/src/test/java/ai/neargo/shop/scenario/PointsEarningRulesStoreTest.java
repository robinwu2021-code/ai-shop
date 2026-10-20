package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import ai.neargo.shop.spi.product.PointsEarningCatalogPort;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;

class PointsEarningRulesStoreTest {
    PointsEarningRules rules;
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:earn_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V540__online_points_earning_rules.sql")).execute(data);
        jdbc = new org.springframework.jdbc.core.JdbcTemplate(data);
        rules = new PointsEarningRules(data,new PointsEarningCatalogPort() {
            public List<Category> categories() { return List.of(new Category("ROOT",null,"食品","食品"),new Category("LEAF","ROOT","水果","食品 / 水果")); }
            public String goodsCategory(String goods) { return "LEAF"; }
            public List<String> ancestry(String c) { return List.of("LEAF","ROOT"); }
        });
        rules.ensureDefault("P",new EarnRule(RuleMode.RATIO,100),7);
    }
    Change command(String id,long rev,String action,Long value) {return new Change(id,"P","ROOT",rev,action,value,"测试");}
    @Test void draftPublishDisableAndHistoryPreservePriorVersions() {
        rules.change(command("A",0,"SAVE",300L),"ops");
        assertThat(rules.resolve("P","LEAF",new EarnRule(RuleMode.RATIO,999),8).rule().value()).isEqualTo(100);
        var published=rules.change(command("B",1,"PUBLISH",null),"ops");
        var snapshot=rules.resolve("P","LEAF",new EarnRule(RuleMode.RATIO,999),8);
        assertThat(snapshot.rule().value()).isEqualTo(300);
        rules.change(command("C",2,"SAVE",0L),"ops");
        assertThat(rules.resolve("P","LEAF",new EarnRule(RuleMode.RATIO,999),8).rule().value()).isEqualTo(300);
        rules.change(command("D",3,"PUBLISH",null),"ops");
        assertThat(rules.resolve("P","LEAF",new EarnRule(RuleMode.RATIO,999),8).rule().value()).isZero();
        rules.change(command("E",4,"DISABLE",null),"ops");
        assertThat(rules.resolve("P","LEAF",new EarnRule(RuleMode.RATIO,999),8).scope()).isEqualTo("DEFAULT");
        assertThat(snapshot.rule().value()).isEqualTo(300);
        assertThat(rules.history("P","ROOT")).hasSize(5);
        assertThat(published.activeRevision()).isEqualTo(2);
    }
    @Test void replayReturnsReceiptButChangedBodyAndStaleVersionAreRejected() {
        var request=command("A",0,"SAVE",200L);
        var original=rules.change(request,"ops");
        assertThat(rules.change(request,"ops")).isEqualTo(original);
        assertThatThrownBy(() -> rules.change(command("A",0,"SAVE",100L),"ops")).hasMessage("POINTS_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> rules.change(command("B",0,"SAVE",100L),"ops")).hasMessage("POINTS_EARN_REVISION_CONFLICT");
    }
    @Test void defaultImportDoesNotOverwriteAndCannotDisableDefault() {
        rules.ensureDefault("P",new EarnRule(RuleMode.FIXED,100),9);
        assertThat(rules.all("P").getFirst().value()).isEqualTo(100);
        assertThatThrownBy(() -> rules.change(new Change("A","P","DEFAULT",1,"DISABLE",null,""),"ops")).hasMessage("POINTS_EARN_DEFAULT_REQUIRED");
    }
    @Test void menuMigrationUsesActualColumnsAndPreservesSettingsGrants() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:earn_menu_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data);
        String schema=new ClassPathResource("schema-test.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String table:List.of("sys_function_point","sys_role_point")) {
            var match=java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS "+table+"\\s*\\([\\s\\S]*?\\n\\);").matcher(schema);
            assertThat(match.find()).isTrue();jdbc.execute(match.group());
        }
        jdbc.update("INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) VALUES('FINANCE','OPS_POINTS__TAB_CONFIGURATION','OPS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V541__points_earning_rules_menu.sql"));
        migration.execute(data);migration.execute(data);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_function_point WHERE href='/points/earning-rules'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_point WHERE role_code='FINANCE' AND point_code='OPS_POINTS__TAB_EARNING_RULES'",Integer.class)).isEqualTo(1);
    }

    @Test void replayIgnoresJsonPropertyOrderAcrossProcessRestarts() {
        var request=command("ORDER",0,"SAVE",200L);
        var original=rules.change(request,"ops");
        String body=jdbc.queryForObject("SELECT command_json FROM ord_points_earn_rule_revision WHERE request_id='ORDER'",String.class);
        var mapper=new tools.jackson.databind.ObjectMapper();
        var node=mapper.readTree(body);
        var entries=new java.util.ArrayList<>(node.properties());java.util.Collections.reverse(entries);
        var reordered=new java.util.LinkedHashMap<String,tools.jackson.databind.JsonNode>();
        for(var entry:entries)reordered.put(entry.getKey(),entry.getValue());
        String reversed=mapper.writeValueAsString(reordered);assertThat(reversed).isNotEqualTo(body);
        jdbc.update("UPDATE ord_points_earn_rule_revision SET command_json=? WHERE request_id='ORDER'",reversed);
        assertThat(rules.change(request,"ops")).isEqualTo(original);
    }

}
