package dev.cordis.example.plugins;

import dev.cordis.core.Context;
import dev.cordis.core.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code db} 服务:由数据库插件提供的内存键值存储。演示 {@link Service}
 * 注册(provide 是提供者 fiber 的一个效应)与拦截:编排者可以通过
 * {@code ctx.intercept("db", ...)} 将任意消费者的访问收缩为只读,
 * 而无需修改提供者或消费者(论文 §6.3)。
 */
public class DbService extends Service {

    public final Map<String, String> rows = new LinkedHashMap<>();
    private final boolean readOnly;

    public DbService(Context ctx) {
        super(ctx, "db");
        Object intercept = interceptConfig();
        this.readOnly = intercept instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("readOnly"));
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void put(String key, String value) {
        if (readOnly) {
            throw new IllegalStateException("db 为只读(已被编排者拦截)");
        }
        rows.put(key, value);
    }

    public String get(String key) {
        return rows.get(key);
    }
}
