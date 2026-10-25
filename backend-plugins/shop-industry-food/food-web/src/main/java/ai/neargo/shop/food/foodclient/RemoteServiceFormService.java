package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ServiceFormContracts;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

public class RemoteServiceFormService implements ServiceFormService {
    private final BusinessOperationsInternalApi api;

    public RemoteServiceFormService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public ServiceFormContracts.OrganizationVO registerOrganization(
            String entity,
            String store,
            String actor,
            ServiceFormContracts.OrganizationReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormRegisterOrganization(
                                new BusinessOperationsInternalApi
                                        .ServiceFormRegisterOrganizationCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO book(
            String entity,
            String store,
            String actor,
            ServiceFormContracts.BookReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormBook(
                                new BusinessOperationsInternalApi.ServiceFormBookCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO get(String entity, String store, String form) {
        return invoke(
                () ->
                        api.serviceFormGet(
                                new BusinessOperationsInternalApi.ServiceFormGetCommand(
                                        entity, store, form)));
    }

    @Override
    public ServiceFormContracts.FormVO revise(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.ReviseReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormRevise(
                                new BusinessOperationsInternalApi.ServiceFormReviseCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO enter(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.EnterReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormEnter(
                                new BusinessOperationsInternalApi.ServiceFormEnterCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO leave(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.VersionReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormLeave(
                                new BusinessOperationsInternalApi.ServiceFormLeaveCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO cancel(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.VersionReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormCancel(
                                new BusinessOperationsInternalApi.ServiceFormCancelCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO noShow(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.VersionReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormNoShow(
                                new BusinessOperationsInternalApi.ServiceFormNoShowCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public ServiceFormContracts.FormVO bindBatch(
            String entity,
            String store,
            String actor,
            String form,
            ServiceFormContracts.BatchReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormBindBatch(
                                new BusinessOperationsInternalApi.ServiceFormBindBatchCommand(
                                        entity, store, actor, form, request, key)));
    }

    @Override
    public List<ServiceFormContracts.AttendeeVO> attendees(
            String entity, String store, String form) {
        return invoke(
                () ->
                        api.serviceFormAttendees(
                                new BusinessOperationsInternalApi.ServiceFormAttendeesCommand(
                                        entity, store, form)));
    }

    @Override
    public ServiceFormContracts.AttendeeVO admit(
            String entity,
            String store,
            String actor,
            String form,
            String attendee,
            ServiceFormContracts.VersionReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormAdmit(
                                new BusinessOperationsInternalApi.ServiceFormAdmitCommand(
                                        entity, store, actor, form, attendee, request, key)));
    }

    @Override
    public ServiceFormContracts.AttendeeVO exit(
            String entity,
            String store,
            String actor,
            String form,
            String attendee,
            ServiceFormContracts.VersionReq request,
            String key) {
        return invoke(
                () ->
                        api.serviceFormExit(
                                new BusinessOperationsInternalApi.ServiceFormExitCommand(
                                        entity, store, actor, form, attendee, request, key)));
    }

    private static <T> T invoke(Supplier<T> call) {
        try {
            T result = ServiceCalls.call(ServiceName.FOOD, call);
            if (result == null) throw new IllegalStateException("Missing FOOD operations receipt");
            return result;
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}
