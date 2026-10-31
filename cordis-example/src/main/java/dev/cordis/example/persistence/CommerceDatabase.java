package dev.cordis.example.persistence;

import dev.cordis.example.domain.Commerce.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Local durable adapter for the example: copy-on-write transactions, atomic snapshots and an exclusive file lock.
 * Orders, sandbox payments and the fulfillment outbox survive plugin replacement and process restart.
 */
public final class CommerceDatabase implements AutoCloseable {
    private final Path file;
    private final String tenantId;
    private final FileChannel channel;
    private final FileLock lock;
    private Snapshot current;
    private boolean closed;
    private boolean inTransaction;

    private static final class Snapshot {
        final Map<String, Product> products = new LinkedHashMap<>();
        final Map<String, Order> orders = new LinkedHashMap<>();
        final Map<String, Payment> payments = new LinkedHashMap<>();
        final Map<String, Boolean> outbox = new LinkedHashMap<>();
        final Map<String, Shipment> shipments = new LinkedHashMap<>();

        Snapshot copy() {
            Snapshot next = new Snapshot();
            next.products.putAll(products);
            next.orders.putAll(orders);
            next.payments.putAll(payments);
            next.outbox.putAll(outbox);
            next.shipments.putAll(shipments);
            return next;
        }
    }

    public static class View {
        private final Snapshot data;

        private View(Snapshot data) {
            this.data = data;
        }

        public Product product(String sku) {
            return data.products.get(sku);
        }

        public List<Product> products() {
            return List.copyOf(data.products.values());
        }

        public Order order(String id) {
            return data.orders.get(id);
        }

        public Order request(String key) {
            return data.orders.values().stream().filter(order -> order.request().requestKey().equals(key)).findFirst().orElse(null);
        }

        public List<Order> orders() {
            return List.copyOf(data.orders.values());
        }

        public Payment payment(String orderId) {
            return data.payments.get(orderId);
        }

        public List<Payment> payments() {
            return List.copyOf(data.payments.values());
        }

        public List<String> pendingDeliveries() {
            return data.outbox.entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).toList();
        }

        public Shipment shipment(String orderId) {
            return data.shipments.get(orderId);
        }

        public List<Shipment> shipments() {
            return List.copyOf(data.shipments.values());
        }
    }

    public static final class Transaction extends View {
        private boolean writable = true;

        private Transaction(Snapshot data) {
            super(data);
        }

        private void check() {
            if (!writable) throw new IllegalStateException("Transaction has ended");
        }

        public void product(Product value) {
            check();
            super.data.products.put(value.sku(), value);
        }

        public void order(Order value) {
            check();
            super.data.orders.put(value.id(), value);
        }

        public void payment(Payment value) {
            check();
            super.data.payments.put(value.orderId(), value);
        }

        public void enqueue(String orderId) {
            check();
            super.data.outbox.putIfAbsent(orderId, false);
        }

        public void delivered(String orderId) {
            check();
            super.data.outbox.put(orderId, true);
        }

        public void removeDelivery(String orderId) {
            check();
            super.data.outbox.remove(orderId);
        }

        public void shipment(Shipment value) {
            check();
            super.data.shipments.put(value.orderId(), value);
        }
    }

    public static CommerceDatabase open(Path file, String tenantId) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        FileChannel channel = FileChannel.open(target.resolveSibling(target.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Business data is already open: " + target);
            return new CommerceDatabase(target, tenantId, channel, lock);
        } catch (Throwable error) {
            if (lock != null) lock.release();
            channel.close();
            throw error;
        }
    }

    private CommerceDatabase(Path file, String tenantId, FileChannel channel, FileLock lock) throws IOException {
        this.file = file;
        this.tenantId = tenantId;
        this.channel = channel;
        this.lock = lock;
        current = Files.exists(file) ? decode(Files.readString(file)) : new Snapshot();
    }

    public String tenantId() {
        return tenantId;
    }

    public synchronized <T> T read(Function<View, T> query) {
        ensureOpen();
        return query.apply(new View(current));
    }

    public synchronized <T> T transaction(Function<Transaction, T> action) {
        ensureOpen();
        if (inTransaction) throw new IllegalStateException("Nested transactions are not supported; pass the current Transaction instead");
        inTransaction = true;
        Snapshot next = current.copy();
        Transaction transaction = new Transaction(next);
        try {
            T result = action.apply(transaction);
            persist(next);
            current = next;
            return result;
        } finally {
            transaction.writable = false;
            inTransaction = false;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Database session is closed");
    }

    private void persist(Snapshot next) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParent(), ".commerce-", ".tmp");
            Files.writeString(temporary, encode(next));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            if (temporary != null) try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    private String encode(Snapshot data) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("tenantId", tenantId);
        document.put("products", data.products.values().stream().map(p -> map("sku", p.sku(), "name", p.name(), "price", p.unitPriceCents(), "stock", p.stock())).toList());
        document.put("orders", data.orders.values().stream().map(o -> map("id", o.id(), "request", requestMap(o.request()),
                "lines", o.lines().stream().map(l -> map("sku", l.sku(), "name", l.name(), "quantity", l.quantity(), "price", l.unitPriceCents())).toList(),
                "subtotal", o.price().subtotalCents(), "discount", o.price().discountCents(), "status", o.status().name(),
                "paymentId", o.paymentId(), "failureCode", o.failureCode(), "createdAt", o.createdAt().toString())).toList());
        document.put("payments", data.payments.values().stream().map(p -> map("id", p.id(), "orderId", p.orderId(), "amount", p.amountCents(), "status", p.status().name())).toList());
        document.put("outbox", data.outbox);
        document.put("shipments", data.shipments.values().stream().map(s -> map("id", s.id(), "orderId", s.orderId(), "address", s.shippingAddress(), "createdAt", s.createdAt().toString())).toList());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(document);
    }

    private Snapshot decode(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document = cast(new Yaml(new SafeConstructor(options)).load(text));
        if (number(document, "version") != 1 || !tenantId.equals(document.get("tenantId")))
            throw new IllegalArgumentException("Unsupported snapshot version or tenant mismatch");
        Snapshot data = new Snapshot();
        for (Map<String, Object> row : rows(document, "products")) {
            Product p = new Product(string(row, "sku"), string(row, "name"), number(row, "price"), Math.toIntExact(number(row, "stock")));
            data.products.put(p.sku(), p);
        }
        for (Map<String, Object> row : rows(document, "orders")) {
            Map<String, Object> r = cast(row.get("request"));
            Checkout request = new Checkout(string(r, "key"), string(r, "customer"), Tier.valueOf(string(r, "tier")),
                    rows(r, "items").stream().map(i -> new CartItem(string(i, "sku"), Math.toIntExact(number(i, "quantity")))).toList(), string(r, "method"), string(r, "address"));
            Order order = new Order(string(row, "id"), request,
                    rows(row, "lines").stream().map(l -> new OrderLine(string(l, "sku"), string(l, "name"), Math.toIntExact(number(l, "quantity")), number(l, "price"))).toList(),
                    new Price(number(row, "subtotal"), number(row, "discount")), OrderStatus.valueOf(string(row, "status")),
                    (String) row.get("paymentId"), (String) row.get("failureCode"), Instant.parse(string(row, "createdAt")));
            data.orders.put(order.id(), order);
        }
        for (Map<String, Object> row : rows(document, "payments")) {
            Payment p = new Payment(string(row, "id"), string(row, "orderId"), number(row, "amount"), PaymentStatus.valueOf(string(row, "status")));
            data.payments.put(p.orderId(), p);
        }
        cast(document.get("outbox")).forEach((id, delivered) -> data.outbox.put(id, (Boolean) delivered));
        for (Map<String, Object> row : rows(document, "shipments")) {
            Shipment s = new Shipment(string(row, "id"), string(row, "orderId"), string(row, "address"), Instant.parse(string(row, "createdAt")));
            data.shipments.put(s.orderId(), s);
        }
        return data;
    }

    private static Map<String, Object> requestMap(Checkout r) {
        return map("key", r.requestKey(), "customer", r.customerId(), "tier", r.tier().name(), "items", r.items().stream().map(i -> map("sku", i.sku(), "quantity", i.quantity())).toList(), "method", r.paymentMethod(), "address", r.shippingAddress());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static String string(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
