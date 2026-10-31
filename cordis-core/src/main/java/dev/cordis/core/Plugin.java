package dev.cordis.core;

import dev.cordis.core.runtime.Context;

import java.util.List;

/**
 * 插件:cordis 里的“组件”。
 *
 * <p>一个插件说清三件事:</p>
 * <ul>
 *   <li><b>需要什么</b> —— {@link #inject()},声明依赖的服务名;</li>
 *   <li><b>提供什么</b> —— 在 apply 里通过 ctx.provide 注册;</li>
 *   <li><b>做什么</b> —— {@link #apply()},启动时执行的逻辑。</li>
 * </ul>
 * <p>同一个插件配上不同的 config,就是两个不同的组件实例。</p>
 */
public interface Plugin {

    /** 插件显示名,用于日志和排查;可为 null(用类名兜底)。 */
    default String name() {
        return null;
    }

    /** 声明依赖的服务名:全都可用时才会启动,缺一个就一直等。 */
    default List<String> inject() {
        return List.of();
    }

    /** 可选的配置校验器:启动前先校验配置;null 表示不校验。 */
    default ConfigSchema<?> schema() {
        return null;
    }

    /**
     * 插件主体:依赖就绪时执行;依赖更换时会先撤销旧的、再重新执行一遍。
     *
     * <p>返回值描述“卸载时怎么撤销”:一个 Disposable、一组 Disposable
     * (多阶段,中间可被打断),或 null。用 ctx.effect / ctx.provide / ctx.on
     * 登记的东西会被框架自动追踪,不需要在返回值里重复。</p>
     *
     * @param ctx    本实例运行所在的上下文
     * @param config 校验后的配置
     * @return 需要撤销的效果,或 null
     */
    Object apply(Context ctx, Object config);

    /** 用一个 lambda 快速包出一个插件。 */
    static Plugin of(String name, List<String> inject, java.util.function.BiFunction<Context, Object, Object> body) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<String> inject() {
                return inject;
            }

            @Override
            public Object apply(Context ctx, Object config) {
                return body.apply(ctx, config);
            }
        };
    }
}
