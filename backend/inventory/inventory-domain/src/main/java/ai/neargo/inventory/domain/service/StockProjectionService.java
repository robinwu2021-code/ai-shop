package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Receipt;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Mapping;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionView;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionPage;
import java.util.List;

/** Projection sessions are isolated from stock postings and reservations. */
public interface StockProjectionService {
    List<MappingResult> validateMappings(Caller caller,Scope scope,List<Mapping> mappings);
    Receipt activate(Caller caller,Scope scope,long generation,String operationKey);
    Receipt deactivate(Caller caller,Scope scope,long generation,String operationKey);
    Receipt apply(Caller caller,Scope scope,long generation,String eventNo,String payloadHash,Snapshot snapshot);
    Receipt queryReceipt(Caller caller,Scope scope,String eventNo,String payloadHash);
    List<Snapshot> queryProjection(Caller caller,Scope scope,List<String> items);
    ProjectionPage list(Caller caller,Scope scope,int page,int size);
    ProjectionView get(Caller caller,Scope scope,String item);
}
