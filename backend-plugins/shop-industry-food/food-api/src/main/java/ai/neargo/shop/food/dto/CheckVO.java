package ai.neargo.shop.food.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dining reception contract; monetary authority remains outside this resource. */
public record CheckVO(String checkNo, String storeNo, LocalDate businessDate, String status, String payMode, int partySize, List<String> tableNos, long version) {    public CheckVO {
        tableNos=tableNos==null ? null:Collections.unmodifiableList(new ArrayList<>(tableNos));
    }
}
