package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;

/** Kitchen configuration is scoped to the trusted store and does not mutate accepted kitchen tasks. */
public interface StationService {
    StationMutationVO createStation(String entity, String store, String actor, CreateStationReq req, String key);
    StationMutationVO updateStation(String entity, String store, String station, String actor,
            UpdateStationReq req, String key);
    GetStationVO getStation(String entity, String store, String station);
    PageData<StationVO> listStations(String entity, String store, Integer page, Integer size);
    OperationVO replaceRoutes(String entity, String store, String sku, String actor,
            ReplaceStationRoutesReq req, String key);
    StationRoutesVO getRoutes(String entity, String store, String sku);
}
