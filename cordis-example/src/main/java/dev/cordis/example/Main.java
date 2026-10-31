package dev.cordis.example;

import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import dev.cordis.example.plugins.MemberPricingPlugin;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;

/** Run a local multi-tenant checkout business, with inspectable durable order/payment/shipment records. */
public final class Main {
    public static void main(String[] args) throws Exception {
        Path directory = args.length == 0 ? Files.createTempDirectory("cordis-commerce-") : Path.of(args[0]);
        Context root = Context.createRoot();
        String firstOrderId;
        try {
            try (CommerceApplication store = new CommerceApplication(root, "shop-a", directory.resolve("shop-a.yaml"));
                 CommerceApplication other = new CommerceApplication(root, "shop-b", directory.resolve("shop-b.yaml"))) {
                store.inventory().seedIfMissing(new Product("coffee", "云南精品咖啡豆 250g", 6800, 50));
                other.inventory().seedIfMissing(new Product("coffee", "云南精品咖啡豆 250g", 9900, 10));

                Order original = store.orders().checkout(request("checkout-001", "test-card", 2));
                firstOrderId = original.id();
                Order retry = store.orders().checkout(request("checkout-001", "test-card", 2));
                require(original.id().equals(retry.id()), "重复提交产生了第二笔订单");
                result("首次下单", original);
                System.out.println("重复提交：返回同一订单，未重复扣库存或扣款。");

                int before = store.inventory().available("coffee");
                try { store.orders().checkout(request("checkout-declined", "declined", 1)); }
                catch (BusinessException error) { require(error.code().equals("PAYMENT_DECLINED"), error.code()); }
                require(before == store.inventory().available("coffee"), "支付失败后库存未释放");
                System.out.println("支付拒绝：订单记录为 PAYMENT_FAILED，库存已释放，没有支付或出库凭证。");

                try { store.auditOrders().checkout(request("audit-write", "test-card", 1)); throw new IllegalStateException("审计角色获得了写权限"); }
                catch (BusinessException error) { require(error.code().equals("READ_ONLY"), error.code()); }
                System.out.println("审计角色：可以查看订单，不能创建订单或发起退款。");

                store.paymentEnabled(false);
                require(store.orders() == null, "支付维护时仍能创建订单");
                require(store.queries().find(firstOrderId) != null, "支付维护影响了历史订单查询");
                store.paymentEnabled(true);
                require(store.orders().find(firstOrderId) != null, "维护导致历史订单丢失");
                System.out.println("支付维护：自动暂停结算，独立读模型仍可查询历史订单。");

                store.replacePricing(new MemberPricingPlugin());
                store.fulfillmentEnabled(false);
                Order promoted = store.orders().checkout(request("checkout-002", "test-card", 2));
                require(promoted.price().discountCents() == 1360, "会员优惠未生效");
                require(store.orders().find(firstOrderId).price().discountCents() == 0, "热更新修改了历史订单金额");
                result("会员促销上线后的新订单", promoted);
                System.out.println("仓配暂停：已支付订单的任务保留在 outbox 中，恢复后补建出库单。");
                store.fulfillmentEnabled(true);
                require(store.fulfillment().pendingCount() == 0, "仓配任务未完成");

                Order isolated = other.orders().checkout(request("checkout-001", "test-card", 1));
                require(isolated.price().payableCents() == 9900, "租户商品价格串用");
                System.out.printf("同一 SKU 的租户隔离：店铺 A 剩余库存 %d，店铺 B 剩余库存 %d，商品价格和订单互不影响。%n",
                        store.inventory().available("coffee"), other.inventory().available("coffee"));
                System.out.printf("店铺 A：%d 个订单记录，%d 笔成功支付，%d 张出库单。%n",
                        store.orders().list().size(), store.payments().capturedCount(), store.fulfillment().shipments().size());
            }
            // Open a fresh database session, forcing a real read from disk after every plugin has unloaded.
            try (CommerceApplication reopened = new CommerceApplication(root, "shop-a", directory.resolve("shop-a.yaml"))) {
                require(reopened.orders().find(firstOrderId).status() == OrderStatus.PAID, "持久化恢复失败");
                System.out.println("关闭全部插件后重新打开数据文件：历史订单、支付回执、出库单仍保留。");
            }
        } finally { root.fiber().dispose(); }
        System.out.println("业务数据目录：" + directory.toAbsolutePath());
        System.out.println("支付与仓配均为本地沙箱适配器，不会实际扣款或联系物流服务。");
    }
    private static Checkout request(String key, String method, int quantity) {
        return new Checkout(key, "customer-1001", Tier.GOLD, List.of(new CartItem("coffee", quantity)), method, "演示仓配地址 A");
    }
    private static void result(String label, Order order) {
        System.out.printf("%s：%s，原价 ¥%s，优惠 ¥%s，实付 ¥%s。%n", label, order.status(),
                money(order.price().subtotalCents()), money(order.price().discountCents()), money(order.price().payableCents()));
    }
    private static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private Main() { }
}
