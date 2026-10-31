package dev.cordis.example.service;

import dev.cordis.core.coeffect.*;
import dev.cordis.core.effect.Disposable;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import java.time.Instant;
import java.util.*;

/** Checkout orchestration with stock reservation, idempotent payment and explicit business compensation. */
public final class OrderService extends Service implements ContextualService<OrderService> {
    private static final class Runtime {
        final Context provider;
        final CommerceDatabase database;
        final InventoryService inventory;
        final PaymentService payments;
        final PricingPolicy pricing;
        boolean active = true;
        Runtime(Context provider, CommerceDatabase db, InventoryService inventory, PaymentService payments, PricingPolicy pricing) {
            this.provider = provider; this.database = db; this.inventory = inventory; this.payments = payments; this.pricing = pricing;
        }
    }
    private final Runtime runtime;
    public OrderService(Context ctx, CommerceDatabase db, InventoryService inventory, PaymentService payments, PricingPolicy pricing) {
        super(ctx, "orders"); runtime = new Runtime(ctx, db, inventory, payments, pricing);
        // Only runtime availability is reverted. Persisted orders and payment records are business history.
        ctx.effect(c -> (Disposable) () -> { synchronized (runtime) { runtime.active = false; } }, "checkout availability");
    }
    private OrderService(Context caller, Runtime runtime) { super(caller, "orders", false); this.runtime = runtime; }
    @Override public OrderService inContext(Context caller) { return new OrderService(caller, runtime); }

    public Order checkout(Checkout request) {
        Order result;
        synchronized (runtime) { result = checkoutLocked(request); }
        // Publish after releasing the business lock: a lifecycle teardown may be waiting for this operation.
        runtime.provider.emit("order/paid", new OrderPaid(runtime.database.tenantId(), result.id()));
        return result;
    }
    private Order checkoutLocked(Checkout request) {
            writable();
            Order existing = runtime.database.read(view -> view.request(request.requestKey()));
            if (existing != null) {
                if (!existing.request().equals(request)) throw new BusinessException("IDEMPOTENCY_CONFLICT", "同一请求键不能提交不同的订单内容");
                if (existing.status() == OrderStatus.PAID) return existing;
                if (existing.status() != OrderStatus.PENDING_PAYMENT) throw new BusinessException(existing.failureCode(), "该请求已经结束: " + existing.status());
                return completePayment(existing);
            }
            Order reserved = runtime.database.transaction(tx -> {
                List<OrderLine> lines = runtime.inventory.lines(tx, request.items());
                Price price = runtime.pricing.price(request.tier(), lines);
                runtime.inventory.reserve(tx, lines);
                Order order = new Order("ord-" + UUID.randomUUID(), request, lines, price, OrderStatus.PENDING_PAYMENT, null, null, Instant.now());
                tx.order(order); return order;
            });
            return completePayment(reserved);
    }
    private Order completePayment(Order reserved) {
        Payment captured;
        try {
            captured = runtime.payments.capture(reserved);
        } catch (BusinessException declined) {
            runtime.database.transaction(tx -> {
                Order current = tx.order(reserved.id());
                if (current.status() == OrderStatus.PENDING_PAYMENT) {
                    runtime.inventory.release(tx, current.lines());
                    tx.order(current.finish(OrderStatus.PAYMENT_FAILED, declined.code()));
                }
                return null;
            });
            throw declined;
        }
        // If this commit fails, the durable pending order and captured receipt remain recoverable.
        // Retrying uses the same payment id; it never charges again or changes the historical quote.
        Order paid = runtime.database.transaction(tx -> {
            Order current = tx.order(reserved.id());
            Order value = current.status() == OrderStatus.PAID ? current : current.paid(captured.id());
            tx.order(value); tx.enqueue(value.id()); return value;
        });
        return paid;
    }
    public Order cancel(String orderId) {
        synchronized (runtime) {
            writable();
            return runtime.database.transaction(tx -> {
                Order order = tx.order(orderId);
                if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
                if (order.status() == OrderStatus.CANCELLED) return order;
                if (order.status() != OrderStatus.PAID) throw new BusinessException("INVALID_ORDER_STATE", "仅允许取消已支付且未出库的订单");
                if (tx.shipment(orderId) != null) throw new BusinessException("ALREADY_FULFILLED", "订单已出库，需走售后退货流程");
                runtime.payments.refund(tx, orderId);
                runtime.inventory.release(tx, order.lines()); tx.removeDelivery(orderId);
                Order cancelled = order.finish(OrderStatus.CANCELLED, "CUSTOMER_CANCELLED"); tx.order(cancelled); return cancelled;
            });
        }
    }
    public Order find(String orderId) { synchronized (runtime) { available(); return runtime.database.read(view -> view.order(orderId)); } }
    public List<Order> list() { synchronized (runtime) { available(); return runtime.database.read(CommerceDatabase.View::orders); } }
    public void recoverPendingPayments() {
        List<Order> recovered = new ArrayList<>();
        synchronized (runtime) {
            available();
            for (Order order : runtime.database.read(CommerceDatabase.View::orders)) if (order.status() == OrderStatus.PENDING_PAYMENT) {
                try { recovered.add(completePayment(order)); }
                catch (BusinessException declined) { /* Failure status and released stock are already persisted. */ }
            }
        }
        for (Order order : recovered)
            runtime.provider.emit("order/paid", new OrderPaid(runtime.database.tenantId(), order.id()));
    }
    private void available() { if (!runtime.active) throw new BusinessException("CHECKOUT_UNAVAILABLE", "结算服务维护中，请稍后重试"); }
    private void writable() {
        available(); Object policy = interceptConfig();
        if (policy instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("readOnly"))) throw new BusinessException("READ_ONLY", "审计角色只能查询订单");
    }
}
