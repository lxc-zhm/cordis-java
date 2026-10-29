package dev.cordis.core;

/**
 * 服务基类:想把自己的实例挂到 ctx 上的类继承它。
 *
 * <p>子类在构造方法里调 super(ctx, 名字),实例立刻注册为当前 fiber 的一个效果;
 * fiber 卸载时自动注销,不用手写清理代码。</p>
 */
public abstract class Service {

    /** 本服务注册所在的上下文。 */
    protected final Context ctx;
    /** 本服务注册用的名字。 */
    public final String name;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.provide(name, this, value -> isAvailable());
    }

    /** “我是否可用”的检查:依赖方加载前会先问一次,默认可用。 */
    protected boolean isAvailable() {
        return true;
    }

    /** 读方上下文里合并后的拦截配置(例如 readOnly=true)。 */
    public Object interceptConfig() {
        return ctx.resolveIntercept(name);
    }
}
