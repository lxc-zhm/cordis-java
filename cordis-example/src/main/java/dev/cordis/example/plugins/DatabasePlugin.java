package dev.cordis.example.plugins;

import dev.cordis.core.runtime.Context;
import dev.cordis.core.runtime.Logger;
import dev.cordis.core.Plugin;

/**
 * 数据库提供者:提供 {@code db} 服务。注意 apply 体:它通过 {@code ctx.effect}
 * 登记了两个效应,不返回任何东西——运行时会自动累加它们的逆变换(打开 → 预编译),
 * 卸载时按 LIFO 顺序执行(关闭语句 → 关闭连接),作者无需编写卸载路径
 * (关注点局部性,论文 §5.3)。
 */
public class DatabasePlugin implements Plugin {

    @Override
    public String name() {
        return "database";
    }

    @Override
    public Object apply(Context ctx, Object config) {
        Logger log = ctx.logger("database");
        log.info("正在打开连接");

        ctx.effect(cb -> {
            log.info("  [连接] 已建立");
            return (dev.cordis.core.effect.Disposable) () -> log.info("  [连接] 已释放");
        }, "open-connection");

        ctx.effect(cb -> {
            log.info("  [语句] 已预编译");
            return (dev.cordis.core.effect.Disposable) () -> log.info("  [语句] 已关闭");
        }, "prepare-statements");

        DbService db = new DbService(ctx);
        if (!db.isReadOnly()) {
            db.put("boot", "ok");
        } else {
            log.info("db 以只读模式就绪;跳过初始化写入");
        }
        log.info("db 就绪 (readOnly={})", db.isReadOnly());
        return null; // 一切需要撤销的都已登记在案
    }
}
