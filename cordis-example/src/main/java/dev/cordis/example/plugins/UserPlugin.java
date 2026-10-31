package dev.cordis.example.plugins;

import dev.cordis.core.runtime.Context;
import dev.cordis.core.runtime.Logger;
import dev.cordis.core.Plugin;

import java.util.List;

/**
 * users 插件:声明 {@code inject: ["db"]}——即余效应规范(论文 Definition 48 的 d)。
 * 在其 realm 内没有 ACTIVE 的提供者解析 {@code db} 时保持 PENDING,提供者出现时激活,
 * 提供者被撤销时回退。通过 {@code ctx.on} 注册的事件监听器同样是效应:卸载时自动移除。
 */
public class UserPlugin implements Plugin {

    @Override
    public String name() {
        return "users";
    }

    @Override
    public List<String> inject() {
        return List.of("db");
    }

    @Override
    public Object apply(Context ctx, Object config) {
        Logger log = ctx.logger("users");
        DbService db = ctx.get("db");
        log.info("已加载,解析到 db@{}", System.identityHashCode(db));

        ctx.on("user:create", args -> {
            log.info("事件 user:create {}", args[0]);
            return null;
        });

        UsersService users = new UsersService(ctx, db);
        if (!db.isReadOnly()) {
            users.create("alice");
        } else {
            log.info("db 只读;跳过种子用户");
        }

        return null; // 监听器 + 服务注册都是被追踪的效应
    }
}
