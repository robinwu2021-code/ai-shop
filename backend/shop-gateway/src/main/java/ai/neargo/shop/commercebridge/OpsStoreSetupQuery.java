package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.PlanTier;
import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.core.catalog.CatalogLockMode;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcCatalogRepository;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only store choices after the OPS controller authenticates its existing creation/review capability. */
@Component
@ConditionalOnProperty(prefix="commerce", name={"enabled", "catalog.enabled", "mall.http.enabled"}, havingValue="true")
public class OpsStoreSetupQuery {
    private final JdbcCatalogRepository catalog;
    private final CommerceUnitOfWork uow;
    private final MerchantQueryPort merchants;
    public OpsStoreSetupQuery(DataSource ds, CommerceUnitOfWork uow, MerchantQueryPort merchants) {
        catalog=new JdbcCatalogRepository(ds);this.uow=uow;this.merchants=merchants;
    }
    public record Choice(String industryCode, String subIndustryCode, String businessTypeCode,
                         String nameZh, String nameTw, String nameEn, int sort,
                         boolean enabled, boolean available) {}

    public List<Choice> choices(ActorContext actor) {
        return uow.required(()->{
            var state=catalog.load(actor.tenantNo(),CatalogLockMode.SHARED);
            var published=state.types.values().stream().filter(t->t.draft().enabled() && t.catalogVersion()>0)
                    .map(t->t.draft().typeCode()).filter(type->state.plans.values().stream().anyMatch(p->{
                        var v=state.planVersions.get(p.currentVersionNo());
                        return p.typeCode().equals(type) && p.tier()==PlanTier.FREE && "ON_SALE".equals(p.saleStatus())
                                && v!=null && "PUBLISHED".equals(v.status());
                    })).collect(Collectors.toSet());
            return choices(merchants.industries(),published);
        });
    }

    static List<Choice> choices(List<MerchantQueryPort.IndustryRow> rows, Set<String> published) {
            var parents=rows.stream().filter(r->r.enabled() && r.subIndustryCode()==null)
                    .map(MerchantQueryPort.IndustryRow::industryCode).collect(Collectors.toSet());
            var selectable=rows.stream().filter(r->r.enabled() && r.subIndustryCode()!=null
                    && parents.contains(r.industryCode()) && published.contains(r.businessTypeCode()))
                    .map(MerchantQueryPort.IndustryRow::industryCode).collect(Collectors.toSet());
            return rows.stream().filter(r->r.enabled() && parents.contains(r.industryCode()))
                    .map(r->new Choice(r.industryCode(),r.subIndustryCode(),r.businessTypeCode(),
                            r.nameZh(),r.nameTw(),r.nameEn(),r.sort(),true,
                            r.subIndustryCode()==null ? selectable.contains(r.industryCode())
                                    : published.contains(r.businessTypeCode()))).toList();
    }
}
