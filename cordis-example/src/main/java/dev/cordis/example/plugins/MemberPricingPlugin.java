package dev.cordis.example.plugins;

import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.service.PricingPolicy;
import java.math.*;

/** New release: GOLD members receive a configurable discount above a minimum spend. */
public final class MemberPricingPlugin extends StandardPricingPlugin {
    @Override public String name() { return "member-pricing"; }
    @Override public Object apply(Context ctx, Object config) {
        Config promotion = (Config) config;
        ctx.provide("pricing", (PricingPolicy) (tier, lines) -> {
            long subtotal = subtotal(lines);
            long discount = tier == Tier.GOLD && subtotal >= promotion.minimumSubtotalCents()
                    ? BigDecimal.valueOf(subtotal).multiply(BigDecimal.valueOf(promotion.goldDiscountBasisPoints()))
                    .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact() : 0;
            return new Price(subtotal, discount);
        }); return null;
    }
}
