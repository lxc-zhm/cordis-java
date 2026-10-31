package dev.cordis.example.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable business data. Amounts are integer CNY cents; no floating point accounting. */
public final class Commerce {
    private Commerce() { }

    public enum Tier { STANDARD, GOLD }
    public enum OrderStatus { PENDING_PAYMENT, PAID, PAYMENT_FAILED, CANCELLED }
    public enum PaymentStatus { CAPTURED, REFUNDED }

    public record Product(String sku, String name, long unitPriceCents, int stock) {
        public Product {
            requireText(sku, "sku"); requireText(name, "product name");
            if (unitPriceCents <= 0 || stock < 0) throw new IllegalArgumentException("Invalid price or stock");
        }
        public Product withStock(int value) { return new Product(sku, name, unitPriceCents, value); }
    }

    public record CartItem(String sku, int quantity) {
        public CartItem {
            requireText(sku, "sku");
            if (quantity < 1 || quantity > 1000) throw new IllegalArgumentException("Quantity must be between 1 and 1000");
        }
    }

    public record Checkout(String requestKey, String customerId, Tier tier, List<CartItem> items,
                           String paymentMethod, String shippingAddress) {
        public Checkout {
            requireText(requestKey, "request key"); requireText(customerId, "customer id");
            requireText(paymentMethod, "sandbox payment method"); requireText(shippingAddress, "shipping address");
            Objects.requireNonNull(tier); items = List.copyOf(items);
            if (items.isEmpty()) throw new IllegalArgumentException("Cart is empty");
        }
    }

    public record OrderLine(String sku, String name, int quantity, long unitPriceCents) {
        public long subtotalCents() { return Math.multiplyExact(unitPriceCents, quantity); }
    }

    public record Price(long subtotalCents, long discountCents) {
        public Price {
            if (subtotalCents < 0 || discountCents < 0 || discountCents > subtotalCents)
                throw new IllegalArgumentException("Invalid quote");
        }
        public long payableCents() { return subtotalCents - discountCents; }
    }

    public record Order(String id, Checkout request, List<OrderLine> lines, Price price, OrderStatus status,
                        String paymentId, String failureCode, Instant createdAt) {
        public Order { lines = List.copyOf(lines); }
        public Order paid(String payment) { return new Order(id, request, lines, price, OrderStatus.PAID, payment, null, createdAt); }
        public Order finish(OrderStatus next, String reason) { return new Order(id, request, lines, price, next, paymentId, reason, createdAt); }
    }

    public record Payment(String id, String orderId, long amountCents, PaymentStatus status) {
        public Payment refunded() { return new Payment(id, orderId, amountCents, PaymentStatus.REFUNDED); }
    }
    public record Shipment(String id, String orderId, String shippingAddress, Instant createdAt) { }
    public record OrderPaid(String tenantId, String orderId) { }

    public static final class BusinessException extends RuntimeException {
        private final String code;
        public BusinessException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
