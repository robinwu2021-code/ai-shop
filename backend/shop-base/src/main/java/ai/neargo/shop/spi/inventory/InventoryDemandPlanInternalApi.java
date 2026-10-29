package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Receipt;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.WithdrawalReceipt;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

public interface InventoryDemandPlanInternalApi {
    String ROOT = "/internal/inventory/demand-plan";

    record Reserve(Plan plan, long ttlSeconds, int lifecycleVersion) {}

    record Reference(Scope scope) {}

    record Withdraw(
            Plan plan,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String operationRef,
            @JsonDeserialize(using = InventoryReservationPort.IntegerValue.class)
                    int lifecycleVersion) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown inventory withdrawal field");
        }
    }

    @PostExchange(ROOT + "/withdraw")
    WithdrawalReceipt withdraw(@RequestBody Withdraw command);

    record Change(
            Scope scope,
            String operationRef,
            String planHash,
            String phase,
            List<Target> targets) {}

    record Reverse(
            Scope scope, String operationRef, String originalOperationRef, String planHash) {}

    @PostExchange(ROOT + "/reserve")
    Receipt reserve(@RequestBody Reserve command);

    @PostExchange(ROOT + "/query")
    Receipt query(@RequestBody Reference command);

    @PostExchange(ROOT + "/accept-unpaid")
    Receipt acceptUnpaid(@RequestBody Reference command);

    @PostExchange(ROOT + "/allocate-paid")
    Receipt allocatePaid(@RequestBody Reference command);

    @PostExchange(ROOT + "/issue-delta")
    Receipt issueDelta(@RequestBody Change command);

    @PostExchange(ROOT + "/issue")
    Receipt issue(@RequestBody Change command);

    @PostExchange(ROOT + "/release")
    Receipt release(@RequestBody Change command);

    @PostExchange(ROOT + "/supplement")
    Receipt supplement(@RequestBody Change command);

    @PostExchange(ROOT + "/reverse")
    Receipt reverse(@RequestBody Reverse command);
}
