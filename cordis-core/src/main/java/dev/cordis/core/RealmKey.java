package dev.cordis.core;

/**
 * 隔离域(realm)的“门牌号”。
 *
 * <p>同一个服务名,在不同隔离域里可以各有一条绑定、互不影响。是否同一个域看对象身份:
 * 两个上下文把某服务名映射到同一个 RealmKey,就共用同一条绑定。
 * 用 {@link #shared(Object)} 可以让相同名字的标签进入同一个共享域(比如多租户共用的域)。</p>
 */
public final class RealmKey {

    private static final java.util.Map<Object, RealmKey> SHARED = new java.util.HashMap<>();

    private final String hint;

    private RealmKey(String hint) {
        this.hint = hint;
    }

    /** 新建一个独享的隔离域。 */
    public static RealmKey fresh(String hint) {
        return new RealmKey(hint);
    }

    /** 取(或创建)与 label 对应的共享域:相同的标签进入同一个域。 */
    public static synchronized RealmKey shared(Object label) {
        return SHARED.computeIfAbsent(label, l -> new RealmKey(String.valueOf(l)));
    }

    @Override
    public String toString() {
        return "realm(" + hint + "@" + Integer.toHexString(System.identityHashCode(this)) + ")";
    }
}
