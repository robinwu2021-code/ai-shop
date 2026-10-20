package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import ai.neargo.shop.support.EntitlementOpenApiExporter;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Documentation and client contracts are checked against live mappings, not against the same source regex twice. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ent-api-contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EntitlementApiContractCoverageTest {
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
    @Test void everyDocumentedPublicRouteHasAHandlerAndItsNamedPermission() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("docs/technical/TDD-活动卡券储值-API契约.md"))) root=root.getParent();
        assertThat(root).as("repository contract must be available").isNotNull();
        var rows=Files.readAllLines(root.resolve("docs/technical/TDD-活动卡券储值-API契约.md"));
        var id=Pattern.compile("[CPO][0-9]+[a-z]?");
        var permission=Pattern.compile("[a-z][a-z0-9_-]*(?::[a-z0-9_-]+){1,}");
        var report=new ArrayList<String>();
        var documented = new java.util.HashSet<String>();
        var exporter = new EntitlementOpenApiExporter(json);
        for(String row:rows) {
            if(!row.startsWith("| ")) continue;
            var columns=row.split("\\|",-1);
            if(columns.length<8 || !id.matcher(columns[1].strip()).matches()) continue;
            String method=columns[2].strip(),path=columns[3].strip();
            if(!Set.of("GET","POST","PUT","DELETE").contains(method) || !path.startsWith("/")) continue;
            var handler=handler(method,path,columns[4].contains("model=unified"));
            if(columns[5].contains("ActivityView")) {
                assertThat(handler.getMethod().getGenericReturnType().getTypeName()).contains("ActivityView");
            }
            var authorize=AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(),PreAuthorize.class);
            if(authorize==null) authorize=AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(),PreAuthorize.class);
            var codes=permission.matcher(columns[6]);
            while(codes.find()) {
                assertThat(authorize).as("%s %s must check %s",method,path,codes.group()).isNotNull();
                assertThat(authorize.value()).as("%s %s",method,path).contains(codes.group());
            }
            exporter.add(method, path, handler, columns[4].contains("model=unified"));
            documented.add(method + " " + path);
            report.add(method+" "+path+" -> "+handler.getBeanType().getSimpleName()+"#"+handler.getMethod().getName());
        }
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                boolean entitlement = java.util.stream.Stream.of("/biz/entitlement-", "/biz/entitlements",
                        "/biz/stored-value/", "/mp/entitlement-", "/mp/entitlements",
                        "/mp/stored-value/", "/ops/pay/entitlement").anyMatch(path::startsWith);
                if (entitlement) {
                    for (var method : info.getMethodsCondition().getMethods()) {
                        assertThat(documented).as("Live entitlement handler must appear in the public contract")
                                .contains(method.name() + " " + path);
                    }
                }
            }
        });
        assertThat(report).as("avoid vacuous success after a table parser change").hasSizeGreaterThanOrEqualTo(50);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/entitlement-api-coverage.txt"),report);
        exporter.write(Path.of("target/entitlement-backend.openapi.json"));
        var specification = json.readTree(Files.readString(Path.of("target/entitlement-backend.openapi.json")));
        assertThat(specification.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(specification.path("paths").path("/biz/activities").path("get").path("parameters").toString())
                .contains("model", "unified");
        assertThat(specification.path("components").path("schemas").size()).isGreaterThan(50);
        System.out.println("Documented entitlement/activity public routes covered: "+report.size());
    }
    @Test void reverseCoreClientMethodsAllHaveLivePostHandlers() {
        int count=0;
        for(var method:CoreEntitlementInternalApi.class.getMethods()) {
            var exchange=method.getAnnotation(PostExchange.class);
            if(exchange==null) continue;
            handler("POST",exchange.value(),false); count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(8);
        System.out.println("Core reverse entitlement handlers covered: "+count);
    }
    @Test void consumerFinancialRecordAndPublicDifferenceDtosCannotExposeInternalPoolsOrCredentials() {
        var forbidden=Set.of("principalminor","bonusminor","heldprincipalminor","heldbonusminor","principaldelta",
                "bonusdelta","authorizationsnapshot","authorizationref","commandsnapshot","paidfactref",
                "membercode","customersnapshot","receiptdigest","receiptreference","expectedsnapshot","actualsnapshot");
        for(var type:List.of(StoredValuePort.StoreItem.class,StoredValuePort.BalanceView.class,
                EntitlementQueryPort.EntitlementItem.class,EntitlementQueryPort.EntitlementDetail.class,
                EntitlementQueryPort.UsageItem.class,EntitlementQueryPort.UsageDetail.class,DiffView.class)) {
            for(var component:type.getRecordComponents()) {
                assertThat(forbidden).as(type.getSimpleName()+"."+component.getName())
                        .doesNotContain(component.getName().toLowerCase(Locale.ROOT));
            }
        }
    }
    private HandlerMethod handler(String method,String path,boolean unified) {
        var matches=mapping.getHandlerMethods().entrySet().stream().filter(entry -> {
            var patterns=entry.getKey().getPathPatternsCondition();
            boolean selection=entry.getKey().getParamsCondition().getExpressions().stream()
                    .filter(expression -> "model".equals(expression.getName()))
                    .allMatch(expression -> "unified".equals(expression.getValue())
                            && expression.isNegated()!=unified);
            if(unified) selection=selection && entry.getKey().getParamsCondition().getExpressions().stream()
                    .anyMatch(expression -> "model".equals(expression.getName()));
            return selection && patterns!=null
                    && patterns.getPatternValues().stream().anyMatch(p -> shape(p).equals(shape(path)))
                    && entry.getKey().getMethodsCondition().getMethods().contains(RequestMethod.valueOf(method));
        }).map(entry -> entry.getValue()).toList();
        assertThat(matches).as(method+" "+path).hasSize(1);
        return matches.getFirst();
    }
    private static String shape(String path) { return path.replaceAll("\\{[^}]+}","{}"); }
}
