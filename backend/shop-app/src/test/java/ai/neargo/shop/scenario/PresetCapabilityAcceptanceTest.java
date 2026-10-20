package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.ActivityPresetViews.PresetView;
import ai.neargo.shop.promotion.mapper.PmtPresetTemplateMapper;
import ai.neargo.shop.promotion.service.ActivityPresetService;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Inspect every shipped recommendation against actual trusted owner evidence, never a client capability list. */
@SpringBootTest(properties={"shop.member-code.enabled=true","shop.core.entitlement.authorization.enabled=true",
        "shop.promotion.activity.enabled=true","shop.promotion.presets.enabled=true",
        "shop.pay.entitlement.catalog.enabled=true","shop.pay.entitlement.issue.enabled=true",
        "shop.pay.entitlement.use.enabled=true","shop.pay.paid-card.enabled=true",
        "shop.trade.paid-card-sale.enabled=true","shop.pay.stored-value.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:preset-acceptance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PresetCapabilityAcceptanceTest {
    @Autowired ActivityPresetService presets;
    @Autowired PmtPresetTemplateMapper rows;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void allFortyActivitiesAndSixPaidProductsHaveKnownRequirementsAndTruthfulAvailability() throws Exception {
        String entity=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,industry,market,tenant_no,"
                + "created_at,updated_at) VALUES(?,?,'matrix','ACTIVE','LIFE_SERVICE','CN','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entity,actor);
        var owner=new ActivityActor("MAIN",entity,actor,null);
        var all=new ArrayList<>(rows.catalog("MAIN","ACTIVITY"));
        all.addAll(rows.catalog("MAIN","PAID_PRODUCT"));
        assertThat(all).hasSize(46);
        var known=Arrays.stream(ActivityCapability.values()).map(Enum::name).toList();
        for(var row:all) {
            assertThat(row.getEnabled()).as(row.getTemplateCode()+" shipped opt-in").isZero();
            for(var requirement:json.readTree(row.getRequiredCapabilities())) {
                assertThat(known).as(row.getTemplateCode()+" requirement").contains(requirement.asText());
            }
        }
        assertThat(presets.list(owner,"ACTIVITY",null,null,null,1,100).records())
                .allSatisfy(p -> assertThat(p.available()).isFalse());
        assertThat(presets.list(owner,"PAID_PRODUCT",null,null,null,1,100).records())
                .allSatisfy(p -> assertThat(p.available()).isFalse());
        // Isolated test DB only: reveal capability evidence without changing any shipped configuration.
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN'");
        var views=new ArrayList<>(presets.list(owner,"ACTIVITY",null,null,null,1,100).records());
        views.addAll(presets.list(owner,"PAID_PRODUCT",null,null,null,1,100).records());
        var executable=Set.of("AP004","AP008","AP009","AP010","AP024","AP030","AP034","AP037",
                "EP001","EP002","EP003","EP004","EP005","EP006");
        var report=new ArrayList<>(List.of("# 预置活动与付费卡能力矩阵", "",
                "来源：PresetCapabilityAcceptanceTest。仅隔离测试库显式开启开关及预置；正式种子46项仍默认关闭。",
                "可执行表示当前实现与运行门禁均满足，不表示每一业态已通过生产部署验收。", "",
                "| 编号 | 名称 | 分类 | 业态 | 场景/模型 | 测试配置可执行 | 缺口 |", "|---|---|---|---|---|---|---|"));
        for(PresetView p:views) {
            assertThat(p.available()).as(p.templateCode()+" "+p.missingCapabilities())
                    .isEqualTo(executable.contains(p.templateCode()));
            if(!p.available()) assertThat(p.missingCapabilities()).contains("PRESET_EXECUTOR");
            report.add("| "+p.templateCode()+" | "+p.name()+" | "+p.category()+" | "
                    +String.join("、",p.industryTags())+" | "+p.draftSchema().path("usageModel").asText(p.scene())
                    +" | "+(p.available()?"是":"否")+" | "+String.join(", ",p.missingCapabilities())+" |");
        }
        Files.write(Path.of("target/entitlement-preset-capabilities.md"),report);
    }
}
