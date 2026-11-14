package ai.neargo.shop.portal.ops;

import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.Filter;
import ai.neargo.shop.commercebridge.PlatformMallAccess;
import ai.neargo.shop.commercebridge.PlatformMallReporting;
import ai.neargo.shop.commercebridge.PlatformMallReporting.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only cross-model platform sales views; original commands remain separately authorized. */
@Profile("ops")
@RestController
@RequestMapping("/ops/platform-mall")
@ConditionalOnProperty(name={"commerce.enabled","commerce.mall.http.enabled","commerce.mall.enabled","commerce.mall.payment.enabled","commerce.mall.offline.enabled"},havingValue="true")
public class OpsPlatformMallController {
  private final PlatformMallAccess access;
  private final PlatformMallReporting reports;
  public OpsPlatformMallController(PlatformMallAccess access,PlatformMallReporting reports){this.access=access;this.reports=reports;}
  @GetMapping("/orders")
  @PreAuthorize("@perm.can('commerce:platform:orders:read')")
  public OrderPage orders(
      @RequestParam(required=false) String model,@RequestParam(required=false) String entityNo,
      @RequestParam(required=false) String storeNo,@RequestParam(required=false) String orderNo,
      @RequestParam(required=false) String status,@RequestParam(required=false) String currency,
      @RequestParam(required=false) Long createdFrom,@RequestParam(required=false) Long createdTo,
      @RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit) {
    var context=access.require("commerce:platform:orders:read",model,false);
    return reports.orders(context,new Filter(entityNo,storeNo,orderNo,status,currency,createdFrom,createdTo,false),after,limit);
  }
  @GetMapping("/claims")
  @PreAuthorize("@perm.can('commerce:platform:claims:read')")
  public OrderPage claims(@RequestParam(required=false) String model,@RequestParam(required=false) String entityNo,
      @RequestParam(required=false) String storeNo,@RequestParam(required=false) String orderNo,
      @RequestParam(required=false) String status,@RequestParam(required=false) String currency,
      @RequestParam(required=false) Long createdFrom,@RequestParam(required=false) Long createdTo,
      @RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit) {
    return reports.orders(access.require("commerce:platform:claims:read",model,true),new Filter(entityNo,storeNo,orderNo,status,currency,createdFrom,createdTo,true),after,limit);
  }
  @GetMapping("/cash")
  @PreAuthorize("@perm.can('commerce:platform:cash:read')")
  public CashPage cash(@RequestParam long fromInclusive,@RequestParam long toExclusive,
      @RequestParam(required=false) String currency,@RequestParam(required=false) String model,
      @RequestParam(required=false) String entityNo,@RequestParam(required=false) String storeNo,
      @RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit) {
    return reports.cash(access.require("commerce:platform:cash:read",model,false),fromInclusive,toExclusive,currency,entityNo,storeNo,after,limit);
  }
}
