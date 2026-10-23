package ai.neargo.shop.food.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dining reception contract; monetary authority remains outside this resource. */
public record CheckDetailVO(CheckVO check, List<SeatReq> seats, List<OccupancyVO> occupancies) {    public CheckDetailVO {
        seats=seats==null ? null:Collections.unmodifiableList(new ArrayList<>(seats));
        occupancies=occupancies==null ? null:Collections.unmodifiableList(new ArrayList<>(occupancies));
    }
}
