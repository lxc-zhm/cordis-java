package dev.cordis.example;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.*;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import dev.cordis.example.plugins.*;
import dev.cordis.example.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CommerceWorkflowTest {
    @TempDir
    Path directory;
    Context root;
    CommerceApplication app;

    @BeforeEach
    void open() {
        root = Context.createRoot();
        app = new CommerceApplication(root, "shop-a", directory.resolve("shop-a.yaml"));
        app.inventory().seedIfMissing(new Product("coffee", "Coffee beans", 6800, 10));
        app.inventory().seedIfMissing(new Product("filter", "Coffee filters", 1200, 1));
    }

    @AfterEach
    void close() {
        if (app != null) app.close();
        root.fiber().dispose();
    }

    private Checkout request(String key, int quantity, String method) {
        return new Checkout(key, "customer-1", Tier.GOLD, List.of(new CartItem("coffee", quantity)), method, "Demo address");
    }

    private CommerceDatabase database() {
        return app.context.get("database");
    }

    private Order byRequest(String key) {
        return database().read(view -> view.request(key));
    }

    @Test
    void paidOrderReservesStockAndCreatesExactlyOnePaymentAndShipment() {
        Order order = app.orders().checkout(request("one", 2, "test-card"));
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(13600, order.price().payableCents());
        assertEquals(8, app.inventory().available("coffee"));
        assertEquals(1, app.payments().capturedCount());
        assertEquals(order.paymentId(), app.payments().receipt(order.id()).id());
        assertEquals(order.id(), app.fulfillment().shipments().getFirst().orderId());
        assertEquals(0, app.fulfillment().pendingCount());
    }

    @Test
    void declinedPaymentReleasesStockAndKeepsFailureHistory() {
        BusinessException failure = assertThrows(BusinessException.class, () -> app.orders().checkout(request("decline", 2, "declined")));
        assertEquals("PAYMENT_DECLINED", failure.code());
        assertEquals(10, app.inventory().available("coffee"));
        assertEquals(OrderStatus.PAYMENT_FAILED, byRequest("decline").status());
        assertEquals(0, app.payments().capturedCount());
        assertTrue(app.fulfillment().shipments().isEmpty());
    }

    @Test
    void insufficientStockRollsBackTheEntireCart() {
        Checkout cart = new Checkout("cart", "customer-1", Tier.STANDARD,
                List.of(new CartItem("coffee", 2), new CartItem("filter", 2)), "test-card", "Demo address");
        assertEquals("INSUFFICIENT_STOCK", assertThrows(BusinessException.class, () -> app.orders().checkout(cart)).code());
        assertEquals(10, app.inventory().available("coffee"));
        assertEquals(1, app.inventory().available("filter"));
        assertNull(byRequest("cart"));
        assertEquals(0, app.payments().capturedCount());
    }

    @Test
    void retriesAreIdempotentAndCannotReuseAKeyWithDifferentContent() {
        Checkout request = request("retry", 2, "test-card");
        Order first = app.orders().checkout(request);
        assertEquals(first, app.orders().checkout(request));
        assertEquals(8, app.inventory().available("coffee"));
        assertEquals(1, app.payments().capturedCount());
        assertEquals(1, app.fulfillment().shipments().size());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(BusinessException.class,
                () -> app.orders().checkout(request("retry", 1, "test-card"))).code());
    }

    @Test
    void concurrentDuplicateRequestsDoNotOversellOrChargeTwice() throws Exception {
        Checkout request = request("concurrent", 2, "test-card");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Order>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(executor.submit(() -> app.orders().checkout(request)));
            String id = results.getFirst().get().id();
            for (Future<Order> result : results) assertEquals(id, result.get().id());
        }
        assertEquals(8, app.inventory().available("coffee"));
        assertEquals(1, app.payments().capturedCount());
        assertEquals(1, app.fulfillment().shipments().size());
    }

    @Test
    void readOnlyRoleCanReadButCannotCreateOrCancelOrders() {
        Order order = app.orders().checkout(request("paid", 1, "test-card"));
        OrderService audit = app.auditOrders();
        assertEquals(order, audit.find(order.id()));
        assertEquals("READ_ONLY", assertThrows(BusinessException.class, () -> audit.checkout(request("forbidden", 1, "test-card"))).code());
        assertEquals("READ_ONLY", assertThrows(BusinessException.class, () -> audit.cancel(order.id())).code());
        assertEquals(1, app.orders().list().size());
        assertEquals(1, app.payments().capturedCount());
    }

    @Test
    void paymentMaintenanceDeactivatesCheckoutAndPreservesBusinessData() {
        OrderService old = app.orders();
        Order order = old.checkout(request("before", 1, "test-card"));
        OrderQueryService queries = app.queries();
        app.paymentEnabled(false);
        assertSame(queries, app.queries());
        assertEquals(order, queries.find(order.id()));
        assertNull(app.orders());
        assertEquals(FiberState.PENDING, app.loader.mounted("orders").fiber.state);
        assertEquals("CHECKOUT_UNAVAILABLE", assertThrows(BusinessException.class, () -> old.checkout(request("during", 1, "test-card"))).code());
        app.paymentEnabled(true);
        assertEquals(order, app.orders().find(order.id()));
        app.orders().checkout(request("after", 1, "test-card"));
        assertEquals(2, app.payments().capturedCount());
    }

    @Test
    void pricingReleaseChangesOnlyNewQuotesAndBrokenReleaseRollsBack() throws Exception {
        Order old = app.orders().checkout(request("original", 2, "test-card"));
        app.replacePricing(new MemberPricingPlugin());
        Order discounted = app.orders().checkout(request("discount", 2, "test-card"));
        assertEquals(1360, discounted.price().discountCents());
        assertEquals(12240, discounted.price().payableCents());
        assertEquals(old, app.orders().find(old.id()));
        Plugin broken = Plugin.of("broken-pricing", List.of(), (ctx, cfg) -> {
            throw new IllegalStateException("Invalid promotion release");
        });
        assertThrows(IllegalStateException.class, () -> app.replacePricing(broken));
        Order restored = app.orders().checkout(request("restored", 2, "test-card"));
        assertEquals(1360, restored.price().discountCents());
        assertNull(root.registry().get(broken));
        assertEquals(old, app.orders().find(old.id()));
    }

    @Test
    void promotionConfigurationIsAppliedWithoutRewritingHistory() throws Exception {
        app.replacePricing(new MemberPricingPlugin());
        Order first = app.orders().checkout(request("ten", 2, "test-card"));
        app.promotion(new StandardPricingPlugin.Config(10000, 1500));
        Order second = app.orders().checkout(request("fifteen", 2, "test-card"));
        assertEquals(1360, first.price().discountCents());
        assertEquals(2040, second.price().discountCents());
        assertEquals(first, app.orders().find(first.id()));
    }

    @Test
    void fulfillmentOutboxCatchesUpAfterPluginIsEnabled() {
        app.fulfillmentEnabled(false);
        Order order = app.orders().checkout(request("queued", 1, "test-card"));
        assertEquals(1, database().read(view -> view.pendingDeliveries().size()).intValue());
        app.fulfillmentEnabled(true);
        assertEquals(order.id(), app.fulfillment().shipments().getFirst().orderId());
        assertEquals(0, app.fulfillment().pendingCount());
        app.fulfillmentEnabled(false);
        app.fulfillmentEnabled(true);
        assertEquals(1, app.fulfillment().shipments().size());
    }

    @Test
    void cancellationRefundsAndReleasesStockOnceBeforeWarehouseDispatch() {
        app.fulfillmentEnabled(false);
        Order order = app.orders().checkout(request("cancel", 2, "test-card"));
        Order cancelled = app.orders().cancel(order.id());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled, app.orders().cancel(order.id()));
        assertEquals(10, app.inventory().available("coffee"));
        assertEquals(PaymentStatus.REFUNDED, app.payments().receipt(order.id()).status());
        app.fulfillmentEnabled(true);
        assertTrue(app.fulfillment().shipments().isEmpty());
    }

    @Test
    void dispatchedOrdersRequireAnAfterSalesFlowInsteadOfBlindRestocking() {
        Order order = app.orders().checkout(request("shipped", 1, "test-card"));
        assertEquals("ALREADY_FULFILLED", assertThrows(BusinessException.class, () -> app.orders().cancel(order.id())).code());
        assertEquals(9, app.inventory().available("coffee"));
        assertEquals(PaymentStatus.CAPTURED, app.payments().receipt(order.id()).status());
    }

    @Test
    void ordersAndReceiptsSurviveClosingEveryPluginAndReopeningDisk() {
        Order order = app.orders().checkout(request("disk", 2, "test-card"));
        app.close();
        app = new CommerceApplication(root, "shop-a", directory.resolve("shop-a.yaml"));
        assertEquals(order, app.orders().find(order.id()));
        assertEquals(order, app.orders().checkout(request("disk", 2, "test-card")));
        assertEquals(8, app.inventory().available("coffee"));
        assertEquals(1, app.payments().capturedCount());
        assertEquals(1, app.fulfillment().shipments().size());
    }

    @Test
    void tenantsHaveSeparateCatalogsInventoryPaymentsOrdersAndEvents() {
        try (CommerceApplication other = new CommerceApplication(root, "shop-b", directory.resolve("shop-b.yaml"))) {
            other.inventory().seedIfMissing(new Product("coffee", "Other shop coffee", 9900, 3));
            Order first = app.orders().checkout(request("same-key", 1, "test-card"));
            assertTrue(other.orders().list().isEmpty());
            assertTrue(other.fulfillment().shipments().isEmpty());
            Order second = other.orders().checkout(request("same-key", 1, "test-card"));
            assertNotEquals(first.id(), second.id());
            assertEquals(6800, first.price().payableCents());
            assertEquals(9900, second.price().payableCents());
            assertEquals(9, app.inventory().available("coffee"));
            assertEquals(2, other.inventory().available("coffee"));
            assertEquals(1, app.fulfillment().shipments().size());
            assertEquals(1, other.fulfillment().shipments().size());
        }
    }

    @Test
    void databaseFileLockPreventsTwoSessionsFromOverwritingTheSameTenant() {
        assertThrows(java.nio.channels.OverlappingFileLockException.class,
                () -> CommerceDatabase.open(directory.resolve("shop-a.yaml"), "shop-a"));
        assertNotNull(app.orders());
    }

    @Test
    void restartRecoversAPaymentCapturedBeforeThePaidOrderCommit() {
        Checkout request = request("interrupted", 1, "test-card");
        Order pending = new Order("interrupted-order", request,
                List.of(new OrderLine("coffee", "Coffee beans", 1, 6800)), new Price(6800, 0),
                OrderStatus.PENDING_PAYMENT, null, null, java.time.Instant.parse("2026-01-01T00:00:00Z"));
        database().transaction(tx -> {
            Product product = tx.product("coffee");
            tx.product(product.withStock(product.stock() - 1));
            tx.order(pending);
            tx.payment(new Payment("original-receipt", pending.id(), 6800, PaymentStatus.CAPTURED));
            return null;
        });
        app.close();
        app = new CommerceApplication(root, "shop-a", directory.resolve("shop-a.yaml"));
        Order recovered = app.orders().find(pending.id());
        assertEquals(OrderStatus.PAID, recovered.status());
        assertEquals("original-receipt", recovered.paymentId());
        assertEquals(1, app.payments().capturedCount());
        assertEquals(9, app.inventory().available("coffee"));
        assertEquals(pending.id(), app.fulfillment().shipments().getFirst().orderId());
    }

}
