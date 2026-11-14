package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;

/** Store-scoped local appointments; external resource-backed and deposit-bearing reservations stay fenced. */
public interface ReservationService {
    PageData<ReservationVO> listReservations(String entityNo,String storeNo,ListReservationsQuery query);
    DetailVO get(String entityNo,String storeNo,String reservationNo);
    DetailVO create(String entityNo,String storeNo,String actorNo,CreateReq req,String key);
    DetailVO change(String entityNo,String storeNo,String actorNo,String reservationNo,ChangeReq req,String key);
    DetailVO cancel(String entityNo,String storeNo,String actorNo,String reservationNo,TransitionReq req,String key);
    DetailVO noShow(String entityNo,String storeNo,String actorNo,String reservationNo,TransitionReq req,String key);
    DetailVO arrive(String entityNo,String storeNo,String actorNo,String reservationNo,ArriveReq req,String key);
}
