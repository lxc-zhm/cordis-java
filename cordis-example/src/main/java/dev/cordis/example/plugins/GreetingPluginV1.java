package dev.cordis.example.plugins;

import dev.cordis.core.Context;
import dev.cordis.core.Logger;
import dev.cordis.core.Plugin;

/**
 * greeting 插件,版本 1——HMR 演示目标。"编辑文件"对应把本类换成
 * {@link GreetingPluginV2}:旧 fiber 的效应逐步展开,新代码的 apply 重新运行,
 * 系统其余部分不受影响。
 */
public class GreetingPluginV1 implements Plugin {

    @Override
    public String name() {
        return "greeting";
    }

    @Override
    public Object apply(Context ctx, Object config) {
        Logger log = ctx.logger("greeting");
        log.info("你好,来自 greeting v1");

        ctx.effect(cb -> {
            log.info("  [greeting 定时器 v1] 已启动");
            return (dev.cordis.core.Disposable) () -> log.info("  [greeting 定时器 v1] 已停止");
        }, "greeting-timer");
        return null;
    }
}
