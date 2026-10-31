package dev.cordis.hmr;

import dev.cordis.core.runtime.Logger;
import dev.cordis.core.Plugin;
import dev.cordis.loader.ComponentLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热更新引擎(HMR,热模块替换):文件改了,把受影响的模块“原地换血”,进程不用重启。
 *
 * <p>为什么能这么做:每个运行实例(fiber)本来就圈住了自己组件的全部效应,
 * 所以换模块 = 卸旧运行实例 + 挂新运行实例,效应自动回滚重放。
 * 不需要像 webpack/vite 那样手写“接受边界”。</p>
 *
 * <p>三个阶段:</p>
 * <ol>
 *   <li>{@linkplain #classify 模块分类} —— 算出哪些模块可热换、哪些拒热换;</li>
 *   <li>{@linkplain #detectStale 陈旧检测} —— 找出引用了变更模块的插件;</li>
 *   <li>{@linkplain #reload 事务性重载} —— 换失败时清理候选并恢复旧插件。</li>
 * </ol>
 */
public final class HmrEngine {

    private static final Logger log = new LoggerServiceHolder().logger;

    private HmrEngine() {
    }

    /**
     * 分类结果:accepted = 可热换的模块;declined = 拒热换的模块(改了只能整体重启)。
     */
    public record Classification(Set<String> accepted, Set<String> declined) {
    }

    /**
     * 简单的控制台日志器(引擎是静态工具类;宿主可自行包装)。
     */
    private static final class LoggerServiceHolder {
        final Logger logger = new dev.cordis.core.runtime.LoggerService().logger("hmr");
    }

    /**
     * 阶段 1:模块分类。从变更文件的引用出发反复推导,直到不再变化(分类不动点):
     * 引用了“可热换模块”的跟着可换;引用到的全是“拒热换”的自己也不能换;
     * 因为循环引用一直定不下来的,一律按“拒热换”处理(保守策略)。
     */
    public static Classification classify(Set<String> stashed, Set<String> externals, ModuleGraph graph) {
        Set<String> accepted = new LinkedHashSet<>(stashed);
        Set<String> declined = new LinkedHashSet<>(externals);
        accepted.removeAll(declined);
        Set<String> pending = new LinkedHashSet<>();
        for (String url : accepted) pending.addAll(minus(graph.imports(url), accepted, declined));
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String url : List.copyOf(pending)) {
                Set<String> imports = graph.imports(url);
                if (!intersection(imports, accepted).isEmpty()) {
                    accepted.add(url);
                    pending.remove(url);
                    progress = true;
                } else if (declined.containsAll(imports)) {
                    declined.add(url);
                    pending.remove(url);
                    progress = true;
                } else if (pending.addAll(minus(imports, accepted, declined))) progress = true;
            }
        }
        declined.addAll(pending);
        return new Classification(Set.copyOf(accepted), Set.copyOf(declined));
    }

    /**
     * 收集一个模块传递引用到的所有模块;拒热换(declined)的当作边界,不再往里走。
     */
    public static Set<String> dependencies(String root, Set<String> declined, ModuleGraph graph) {
        Set<String> deps = new LinkedHashSet<>();
        traverse(root, deps, declined, graph);
        return deps;
    }

    private static void traverse(String url, Set<String> deps, Set<String> declined, ModuleGraph graph) {
        if (deps.contains(url) || declined.contains(url)) return;
        deps.add(url);
        for (String child : graph.imports(url)) traverse(child, deps, declined, graph);
    }

    /**
     * 阶段 2:陈旧检测。一个插件只要依赖树里出现过“可热换的模块”,就算陈旧,
     * 需要重装;树里其余陈旧模块一并作废。
     *
     * @param entryUrls 所有已挂载配置条目的 url
     * @return 陈旧配置条目的 url 集合
     */
    public static Set<String> detectStale(Collection<String> entryUrls, Set<String> accepted,
                                          Set<String> declined, ModuleGraph graph) {
        Set<String> staleEntries = new LinkedHashSet<>();
        Set<String> acceptedMutable = new HashSet<>(accepted);
        boolean progress;
        do {
            progress = false;
            for (String url : entryUrls) {
                Set<String> tree = dependencies(url, declined, graph);
                if (!intersection(tree, acceptedMutable).isEmpty()) {
                    acceptedMutable.addAll(tree);
                    if (staleEntries.add(url)) progress = true;
                }
            }
        } while (progress);
        return staleEntries;
    }

    /**
     * 阶段 3:事务性重载。逐个“卸旧运行实例、装新模块”;
     * 中途任何一个失败,就把已经换过的全部从旧组件重建回去,再把错误抛出——
     * 恢复失败会作为 suppressed 异常一并报告;用户逆操作的正确性仍由组件保证。
     *
     * @param loader      管理这些配置条目的加载器
     * @param staleIds    要重载的配置条目 id
     * @param fresh       url → 新编译的插件;缺省(或没有对应 url)就重新走解析器
     * @param backupStore 可选,接收每个 id 换下来的旧插件(备份)
     */
    public static void reload(ComponentLoader loader, Collection<String> staleIds,
                              Map<String, Plugin> fresh, Map<String, Plugin> backupStore) throws Exception {
        loader.transaction(() -> {
            reloadNow(loader, staleIds, fresh, backupStore);
            return null;
        });
    }

    private static void reloadNow(ComponentLoader loader, Collection<String> staleIds,
                                  Map<String, Plugin> fresh, Map<String, Plugin> backupStore) throws Exception {
        synchronized (loader) {
            Map<String, Plugin> backup = new java.util.LinkedHashMap<>();
            Map<String, Plugin> replacements = new java.util.LinkedHashMap<>();
            // Import every candidate before withdrawing any working instance.
            for (String id : new LinkedHashSet<>(staleIds)) {
                ComponentLoader.Mounted mounted = loader.mounted(id);
                if (mounted == null || mounted.fiber == null || mounted.entry.disabled) continue;
                backup.put(id, mounted.plugin);
                Plugin replacement = fresh == null ? null : fresh.get(mounted.entry.url);
                replacements.put(id, replacement != null ? replacement : loader.resolve(mounted.entry.url));
            }
            if (backupStore != null) backupStore.putAll(backup);
            try {
                for (String id : backup.keySet()) loader.detachForReload(id);
                for (var entry : replacements.entrySet()) loader.remount(entry.getKey(), entry.getValue());
            } catch (Throwable failure) {
                // First remove ALL candidates; only then restore old providers and consumers.
                for (String id : backup.keySet()) {
                    try {
                        loader.detachForReload(id);
                    } catch (Throwable error) {
                        if (failure != error) failure.addSuppressed(error);
                    }
                }
                for (var entry : backup.entrySet()) {
                    try {
                        loader.remount(entry.getKey(), entry.getValue());
                    } catch (Throwable error) {
                        if (failure != error) failure.addSuppressed(error);
                    }
                }
                throw dev.cordis.core.effect.Stages.propagate(failure);
            }
        }
    }

    private static Set<String> minus(Set<String> set, Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(set);
        out.removeAll(a);
        out.removeAll(b);
        return out;
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    /**
     * 辅助方法:把陈旧的 url 对应回加载器里的配置条目 id。
     */
    public static List<String> idsForUrls(ComponentLoader loader, Set<String> staleUrls) {
        List<String> ids = new ArrayList<>();
        for (String id : loader.ids()) {
            ComponentLoader.Mounted m = loader.mounted(id);
            if (m != null && m.entry != null && staleUrls.contains(m.entry.url)) ids.add(id);
        }
        return ids;
    }
}
