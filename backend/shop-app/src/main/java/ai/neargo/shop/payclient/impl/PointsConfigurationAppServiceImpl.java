package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
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
import ai.neargo.shop.payclient.PointsConfigurationAppService;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PointsConfigurationAppServiceImpl implements PointsConfigurationAppService {
    private final PointsAdministrationService service;

    public PointsConfigurationAppServiceImpl(PointsAdministrationService service) {
        this.service = service;
    }

    @Override
    public List<PointType> types() {
        return service.types();
    }

    @Override
    public ChangeResult create(Create r, String actor) {
        return service.create(r, actor);
    }

    @Override
    public ChangeResult transition(TypeChange r, String actor) {
        return service.transition(r, actor);
    }

    @Override
    public FallbackRule fallback(String type) {
        return service.fallback(type);
    }

    @Override
    public ChangeResult saveFallback(FallbackChange r, String actor) {
        return service.saveFallback(r, actor);
    }

    @Override
    public SwitchPage switches(String type, long after, int limit) {
        return service.switches(type, after, limit);
    }

    @Override
    public ChangeResult changeSwitch(SwitchChange r, String actor) {
        return service.changeSwitch(r, actor);
    }

    @Override
    public Terms terms(String type) {
        return service.terms(type);
    }

    @Override
    public ChangeResult publishTerms(TermsChange r, String actor) {
        return service.publishTerms(r, actor);
    }

    @Override
    public AgreementPage agreements(String type, String store, long after, int limit) {
        return service.agreements(type, store, after, limit);
    }
}
