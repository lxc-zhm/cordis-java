package dev.cordis.example.plugins;

import dev.cordis.core.*;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.service.PricingPolicy;
import java.util.List;

/** Original checkout rule: every customer pays the catalog price. */
public class StandardPricingPlugin implements Plugin {
    public record Config(long minimumSubtotalCents, int goldDiscountBasisPoints) {
        public Config {
            if (minimumSubtotalCents < 0 || goldDiscountBasisPoints < 0 || goldDiscountBasisPoints > 10000)
                throw new IllegalArgumentException("Invalid promotion threshold or discount rate");
        }
    }
    @Override public String name() { return "standard-pricing"; }
    @Override public ConfigSchema<?> schema() { return raw -> {
        if (!(raw instanceof Config)) throw new IllegalArgumentException("Pricing requires a typed Config"); return raw;
    }; }
    @Override public Object apply(Context ctx, Object config) {
        ctx.provide("pricing", (PricingPolicy) (tier, lines) -> new Price(subtotal(lines), 0)); return null;
    }
    protected static long subtotal(List<OrderLine> lines) {
        try {
            long amount = 0; for (OrderLine line : lines) amount = Math.addExact(amount, line.subtotalCents()); return amount;
        } catch (ArithmeticException error) { throw new BusinessException("AMOUNT_OVERFLOW", "订单金额超出支持范围"); }
    }
}
