package dev.cordis.example;

import dev.cordis.core.Context;
import dev.cordis.core.Fiber;
import dev.cordis.core.FiberState;
import dev.cordis.core.Logger;
import dev.cordis.core.Plugin;
import dev.cordis.core.PluginRuntime;
import dev.cordis.hmr.HmrEngine;
import dev.cordis.hmr.ModuleGraph;
import dev.cordis.loader.ComponentLoader;
import dev.cordis.loader.Entry;
import dev.cordis.example.plugins.DatabasePlugin;
import dev.cordis.example.plugins.DbService;
import dev.cordis.example.plugins.GreetingPluginV1;
import dev.cordis.example.plugins.GreetingPluginV2;
import dev.cordis.example.plugins.UserPlugin;
import dev.cordis.example.plugins.UsersService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 插件实例演示。依次展示本移植版的全部能力:
 *
 * <ol>
 *   <li>响应式余效应——消费者等待(PENDING)其提供者,随后激活;撤销提供者会使其
 *       去激活并回退其效应;重新提供则重新激活;</li>
 *   <li>时间可组合性——卸载时效应按 LIFO 顺序逆转,全程可观察;</li>
 *   <li>声明式加载器——按 entry id 进行调和;</li>
 *   <li>隔离——两个租户共享同一插件代码,但把 {@code db} 解析到不同 realm;</li>
 *   <li>拦截——编排者授予只读 db,无需改动提供者或消费者;</li>
 *   <li>HMR——"编辑"greeting 模块,仅其 fiber 被替换,故障模块自动回滚。</li>
 * </ol>
 */
public final class Main {

    /** 模拟的模块注册表:url → 返回"编译后"模块的工厂。 */
    static final Map<String, Supplier<Plugin>> MODULES = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        MODULES.put("plugin-database", DatabasePlugin::new);
        MODULES.put("plugin-users", UserPlugin::new);
        MODULES.put("plugin-greeting", GreetingPluginV1::new);

        Context root = Context.createRoot();
        // 把生命周期状态迁移打印到控制台
        root.onGlobal("internal/status", st -> {
            Object[] a = st;
            Fiber fiber = (Fiber) a[0];
            FiberState old = (FiberState) a[1];
            root.logger("cordis").info("  状态: {} {} → {}", fiber.name(), old, fiber.state);
            return null;
        });

        ComponentLoader loader = newLoader(root);

        banner("1. 响应式余效应:users 声明 db,直到 db ACTIVE 才加载");
        loader.load(List.of(
                Entry.of("users", "plugin-users").build(),
                Entry.of("database", "plugin-database").build()));
        printStates(root);
        UsersService users = (UsersService) root.getOrNull("users");
        users.create("bob");
        root.emit("user:create", "carol");

        banner("2. 时间可组合性:移除 database,其触碰过的一切都被逆转");
        loader.load(List.of(Entry.of("users", "plugin-users").build()));
        printStates(root);

        banner("3. 重新提供:users 针对新的提供者 fiber 重新激活");
        loader.load(List.of(
                Entry.of("users", "plugin-users").build(),
                Entry.of("database", "plugin-database").build()));
        printStates(root);
        users = (UsersService) root.getOrNull("users");
        users.create("dave");

        banner("4. 声明式加载器:按 id 调和(配置更新 + 禁用 + 移除)");
        loader.load(List.of(
                Entry.of("users", "plugin-users").build(),
                Entry.of("database", "plugin-database").build(),
                Entry.of("greeting", "plugin-greeting").build()));
        loader.load(List.of(
                Entry.of("users", "plugin-users").build(),
                Entry.of("database", "plugin-database").build(),
                Entry.of("greeting", "plugin-greeting").disabled(true).build()));

        banner("5. 隔离:两个租户,同一插件,两个 db realm");
        Context tenantA = root.isolate("db", "db@tenant-a").isolate("users", "users@tenant-a");
        Context tenantB = root.isolate("db", "db@tenant-b").isolate("users", "users@tenant-b");
        ComponentLoader loaderA = newLoader(tenantA);
        ComponentLoader loaderB = newLoader(tenantB);
        List<Entry> tenantEntries = List.of(
                Entry.of("database", "plugin-database").build(),
                Entry.of("users", "plugin-users").build());
        loaderA.load(tenantEntries);
        loaderB.load(tenantEntries);
        DbService dbA = tenantA.get("db");
        DbService dbB = tenantB.get("db");
        dbA.put("tenant", "A");
        dbB.put("tenant", "B");
        System.out.println("租户-a 的 db 读到 tenant=" + dbA.get("tenant")
                + ";租户-b 的 db 读到 tenant=" + dbB.get("tenant")
                + "(实例相互独立:" + (dbA != dbB) + ")");

        banner("6. 拦截:租户-b 的 db 变为只读,不触碰提供者与消费者");
        loaderB.disposeAll(); // 释放租户-b 的 realm(每个 realm 内一个 key 只有一个提供者)
        Context tenantBPoliced = tenantB.intercept("db", Map.of("readOnly", true));
        ComponentLoader loaderBP = newLoader(tenantBPoliced);
        loaderBP.load(tenantEntries);
        DbService dbBP = tenantBPoliced.get("db");
        try {
            dbBP.put("hack", "nope");
        } catch (IllegalStateException e) {
            System.out.println("写入被按设计拒绝:" + e.getMessage());
        }

        banner("7. HMR:编辑 greeting 模块——仅其 fiber 被替换");
        loader.disposeAll(); // 释放全局 db realm,供 hmr 加载器使用
        ComponentLoader hmrLoader = newLoader(root);
        hmrLoader.load(List.of(
                Entry.of("database", "plugin-database").build(),
                Entry.of("greeting", "plugin-greeting").build()));

        // 开发者保存了编辑后的文件:模块注册表现在产出 v2
        MODULES.put("plugin-greeting", GreetingPluginV2::new);
        Map<String, Plugin> fresh = new HashMap<>();
        fresh.put("plugin-greeting", new GreetingPluginV2());

        ModuleGraph graph = url -> Set.of(); // 本演示中皆为叶子模块
        HmrEngine.Classification cls =
                HmrEngine.classify(Set.of("plugin-greeting"), Set.of(), graph);
        Set<String> staleUrls = HmrEngine.detectStale(
                hmrLoader.ids().stream().map(id -> hmrLoader.mounted(id).entry.url).toList(),
                cls.accepted(), cls.declined(), graph);
        System.out.println("hmr 分类结果 accepted=" + cls.accepted() + " declined=" + cls.declined()
                + ";陈旧条目=" + HmrEngine.idsForUrls(hmrLoader, staleUrls));
        HmrEngine.reload(hmrLoader, HmrEngine.idsForUrls(hmrLoader, staleUrls), fresh, null);

        banner("8. HMR 回滚:损坏的模块绝不会让系统进入半重载状态");
        MODULES.put("plugin-greeting", () -> {
            throw new RuntimeException("编辑后的 greeting.ts 存在语法错误");
        });
        Map<String, Plugin> broken = new HashMap<>();
        try {
            HmrEngine.reload(hmrLoader, List.of("greeting"), broken, null);
        } catch (Exception e) {
            System.out.println("重载按预期失败:" + e.getMessage());
        }
        System.out.println("回滚后的 greeting fiber:#" + hmrLoader.mounted("greeting").fiber.uid
                + "(上一组件仍在服务)");

        banner("结束——释放 root,级联贯穿每一个 fiber");
        loader.disposeAll();
        loaderA.disposeAll();
        loaderBP.disposeAll();
        hmrLoader.disposeAll();
    }

    private static ComponentLoader newLoader(Context parent) {
        return new ComponentLoader(parent, url -> {
            Supplier<Plugin> factory = MODULES.get(url);
            if (factory == null) throw new IllegalArgumentException("模块未找到:" + url);
            return factory.get();
        });
    }

    private static void printStates(Context root) {
        StringBuilder sb = new StringBuilder("注册表:");
        for (PluginRuntime rt : root.registry().runtimes()) {
            for (Fiber f : rt.fibers) sb.append("  ").append(f.name()).append("#").append(f.uid).append("=").append(f.state);
        }
        System.out.println(sb);
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }

    private Main() {
    }
}
