package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService.AgreementPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.ChangeResult;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Create;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackRule;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Terms;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TermsChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeChange;
import ai.neargo.shop.pay.points.model.PointType;

import java.util.List;

/**
 * Server-authenticated operator requests delegate to domain transactions without becoming a domain
 * service bean.
 */
public interface PointsConfigurationAppService {
    List<PointType> types();

    ChangeResult create(Create request, String actor);

    ChangeResult transition(TypeChange request, String actor);

    FallbackRule fallback(String typeCode);

    ChangeResult saveFallback(FallbackChange request, String actor);

    SwitchPage switches(String typeCode, long after, int limit);

    ChangeResult changeSwitch(SwitchChange request, String actor);

    Terms terms(String typeCode);

    ChangeResult publishTerms(TermsChange request, String actor);

    AgreementPage agreements(String typeCode, String storeNo, long after, int limit);
}
