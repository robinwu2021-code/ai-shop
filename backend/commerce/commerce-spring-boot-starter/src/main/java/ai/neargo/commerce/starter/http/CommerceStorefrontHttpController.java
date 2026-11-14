package ai.neargo.commerce.starter.http;

import ai.neargo.commerce.api.mall.StorefrontFacade;

import org.springframework.web.bind.annotation.*;

/** Merchant storefront: what this store can currently buy, with server-side readiness. */
@RestController
@RequestMapping("/biz/stores/{storeNo}/mall")
public final class CommerceStorefrontHttpController extends MallHttpSupport {
    private final StorefrontFacade storefront;

    public CommerceStorefrontHttpController(
            StorefrontFacade storefront, MallHttpContextPort context, MallHttpEnvelopePort envelope) {
        super(context, envelope);
        this.storefront = storefront;
    }

    @GetMapping("/products")
    public Object products(@PathVariable("storeNo") String store) {
        var c = context.buyer(store, "commerce:mall:browse");
        return ok(storefront.catalog(c.actor(), c.store()));
    }
}
