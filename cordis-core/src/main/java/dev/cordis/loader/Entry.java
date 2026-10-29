package dev.cordis.loader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条声明式配置项:用数据描述“要装哪个插件、怎么装”,加载器负责照着办。
 *
 * <p>一条 Entry 决定一个 fiber 的命运:id 是身份、url 选插件、config 给配置、
 * disabled 决定装不装、isolate/intercept 调整上下文。</p>
 */
public final class Entry {

    /** 稳定身份:调和时靠它区分“是改了,还是删了又加”。 */
    public final String id;
    /** 插件模块的地址(交给解析器解析)。 */
    public final String url;
    /** 隔离声明:服务名 → TRUE(独享域)或共享标签(同名共用一个域)。 */
    public final Map<String, Object> isolate;
    /** 拦截声明:服务名 → 追加给该服务的配置。 */
    public final Map<String, Object> intercept;
    /** 传给插件的配置。 */
    public final Object config;
    /** 是否被管理员关掉(条目保留,但不挂载)。 */
    public final boolean disabled;

    private Entry(Builder b) {
        this.id = b.id;
        this.url = b.url;
        this.isolate = Map.copyOf(b.isolate);
        this.intercept = Map.copyOf(b.intercept);
        this.config = b.config;
        this.disabled = b.disabled;
    }

    public static Builder of(String id, String url) {
        return new Builder(id, url);
    }

    public static final class Builder {
        private final String id;
        private final String url;
        private final Map<String, Object> isolate = new LinkedHashMap<>();
        private final Map<String, Object> intercept = new LinkedHashMap<>();
        private Object config;
        private boolean disabled;

        Builder(String id, String url) {
            this.id = id;
            this.url = url;
        }

        public Builder isolate(String name) {
            isolate.put(name, Boolean.TRUE);
            return this;
        }

        /** 加入具名的共享域:写了相同标签的 Entry 共用同一个域。 */
        public Builder isolateShared(String name, Object label) {
            isolate.put(name, label);
            return this;
        }

        public Builder intercept(String name, Object config) {
            intercept.put(name, config);
            return this;
        }

        public Builder config(Object config) {
            this.config = config;
            return this;
        }

        public Builder disabled(boolean disabled) {
            this.disabled = disabled;
            return this;
        }

        public Entry build() {
            return new Entry(this);
        }
    }

    @Override
    public String toString() {
        return "Entry(" + id + " → " + url + (disabled ? ", disabled" : "") + ")";
    }
}
