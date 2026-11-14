package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.ServiceFormContracts.AttendeeVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.BatchReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.BookReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.EnterReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.FormVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.OrganizationReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.OrganizationVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.ReviseReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.VersionReq;

import java.util.List;

public interface ServiceFormService {
    OrganizationVO registerOrganization(
            String entity, String store, String actor, OrganizationReq request, String key);

    FormVO book(String entity, String store, String actor, BookReq request, String key);

    FormVO get(String entity, String store, String form);

    FormVO revise(
            String entity, String store, String actor, String form, ReviseReq request, String key);

    FormVO enter(
            String entity, String store, String actor, String form, EnterReq request, String key);

    FormVO leave(
            String entity, String store, String actor, String form, VersionReq request, String key);

    FormVO cancel(
            String entity, String store, String actor, String form, VersionReq request, String key);

    FormVO noShow(
            String entity, String store, String actor, String form, VersionReq request, String key);

    FormVO bindBatch(
            String entity, String store, String actor, String form, BatchReq request, String key);

    List<AttendeeVO> attendees(String entity, String store, String form);

    AttendeeVO admit(
            String entity,
            String store,
            String actor,
            String form,
            String attendee,
            VersionReq request,
            String key);

    AttendeeVO exit(
            String entity,
            String store,
            String actor,
            String form,
            String attendee,
            VersionReq request,
            String key);
}
