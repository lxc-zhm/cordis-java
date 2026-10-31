package dev.cordis.example.service;

import dev.cordis.example.domain.Commerce.*;

import java.util.List;

@FunctionalInterface
public interface PricingPolicy {
    Price price(Tier tier, List<OrderLine> lines);
}
