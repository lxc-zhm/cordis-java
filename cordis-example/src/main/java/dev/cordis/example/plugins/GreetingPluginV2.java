package dev.cordis.example.plugins;

import dev.cordis.core.Context;
import dev.cordis.core.Logger;
import dev.cordis.core.Plugin;

/**
 * greeting 插件,版本 2——"编辑"之后的模块。
 */
public class GreetingPluginV2 implements Plugin {

    @Override
    public String name() {
        return "greeting";
    }

    @Override
    public Object apply(Context ctx, Object config) {
        Logger log = ctx.logger("greeting");
        log.info("你好,来自 greeting v2(已编辑)");

        ctx.effect(cb -> {
            log.info("  [greeting 定时器 v2] 已启动");
            return (dev.cordis.core.Disposable) () -> log.info("  [greeting 定时器 v2] 已停止");
        }, "greeting-timer");
        return null;
    }
}
