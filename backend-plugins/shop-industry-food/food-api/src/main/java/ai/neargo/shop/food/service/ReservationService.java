package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;

/** Only the independent read side is implemented until shared hold/release authority exists. */
public interface ReservationService {
    PageData<ReservationVO> listReservations(String entityNo,String storeNo,ListReservationsQuery query);
}
