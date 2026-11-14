package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.EventFeedService;
import ai.neargo.inventory.domain.service.EventFeedService.Page;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件拉取与确认（TDD §2.12）。宿主拉到一页处理完，再确认最后一个序号；不确认就会再次拉到（至少一次）。
 */
@RestController
public class EventController {

    private final EventFeedService feed;

    public EventController(EventFeedService feed) {
        this.feed = feed;
    }

    @GetMapping("/api/inventory/v1/events")
    public Page pull(@RequestParam(required = false) Long afterSeq, @RequestParam(defaultValue = "100") int limit) {
        return feed.pull(InventoryRequestContext.caller(), afterSeq, limit);
    }

    @PostMapping("/api/inventory/v1/event-acks")
    public Ack ack(@RequestBody Ack req) {
        feed.ack(InventoryRequestContext.caller(), req.seq());
        return req;
    }

    public record Ack(long seq) {
    }
}
