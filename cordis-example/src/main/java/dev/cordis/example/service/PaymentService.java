package dev.cordis.example.service;

import dev.cordis.core.coeffect.Service;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import java.util.UUID;

/** Local sandbox payment adapter: durable idempotency/receipts, no network or real charges. */
public final class PaymentService extends Service {
    private final CommerceDatabase database;
    public PaymentService(Context ctx, CommerceDatabase database) { super(ctx, "payments"); this.database = database; }
    Payment capture(Order order) {
        return database.transaction(tx -> {
            Payment existing = tx.payment(order.id());
            if (existing != null) {
                if (existing.amountCents() != order.price().payableCents() || existing.status() != PaymentStatus.CAPTURED)
                    throw new BusinessException("PAYMENT_CONFLICT", "支付状态或金额不一致");
                return existing;
            }
            if (order.request().paymentMethod().equals("declined")) throw new BusinessException("PAYMENT_DECLINED", "沙箱支付通道拒绝交易");
            if (!order.request().paymentMethod().equals("test-card")) throw new BusinessException("INVALID_PAYMENT_METHOD", "仅支持沙箱 test-card/declined");
            Payment payment = new Payment("pay-" + UUID.randomUUID(), order.id(), order.price().payableCents(), PaymentStatus.CAPTURED);
            tx.payment(payment); return payment;
        });
    }
    void refund(CommerceDatabase.Transaction tx, String orderId) {
        Payment payment = tx.payment(orderId);
        if (payment != null && payment.status() == PaymentStatus.CAPTURED) tx.payment(payment.refunded());
    }
    public long capturedCount() { return database.read(view -> view.payments().stream().filter(p -> p.status() == PaymentStatus.CAPTURED).count()); }
    public Payment receipt(String orderId) { return database.read(view -> view.payment(orderId)); }
}
