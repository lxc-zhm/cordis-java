package dev.cordis.core.effect;

/**
 * 一个“撤销动作”:描述之前做的某件事怎么被收回。
 *
 * <p>例如“打开连接”要配套一个“关闭连接”。组件卸载时,框架按
 * “后登记的先执行”的顺序调用这些动作,保证现场被完整还原
 * (即论文 §3.1 的“可回退效应”)。</p>
 */
@FunctionalInterface
public interface Disposable {

    /** 执行撤销。框架保证只调用一次,不用担心重复执行。 */
    void dispose();

    /** 什么也不做的撤销动作(组合时的“零”)。 */
    static Disposable noop() {
        return () -> {};
    }

    /**
     * 组合两个撤销动作:先执行 before,再执行自己。
     * 每登记一个新动作就把它“垫”到最前面,卸载时自然形成“后进先出”的回滚顺序。
     */
    default Disposable composeAfter(Disposable before) {
        Disposable self = this;
        return () -> {
            if (before != null) before.dispose();
            self.dispose();
        };
    }

    /** 依次执行:先 first,后 second。 */
    static Disposable sequence(Disposable first, Disposable second) {
        return () -> {
            if (first != null) first.dispose();
            if (second != null) second.dispose();
        };
    }
}
