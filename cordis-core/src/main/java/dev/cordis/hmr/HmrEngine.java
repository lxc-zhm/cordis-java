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
 * 热替换(HMR)引擎:文件改了,把受影响的模块“原地换血”,进程不用重启。
 *
 * <p>为什么能这么做:每个 fiber 本来就圈住了自己组件的全部效果,
 * 所以换模块 = 卸旧 fiber + 挂新 fiber,效果自动回滚重放。
 * 不需要像 webpack/vite 那样手写“接受边界”。</p>
 *
 * <p>三个阶段:</p>
 * <ol>
 *   <li>{@linkplain #classify 分类} —— 算出哪些模块要换、哪些不能换;</li>
 *   <li>{@linkplain #detectStale 陈旧检测} —— 找出引用了变更模块的插件;</li>
 *   <li>{@linkplain #reload 事务重载} —— 换失败就整体回滚,绝不停在半换状态。</li>
 * </ol>
 */
public final class HmrEngine {

    private static final Logger log = new LoggerServiceHolder().logger;

    private HmrEngine() {
    }

    /** 分类结果:accepted = 要热替换的模块;declined = 不能换的(改了只能整体重启)。 */
    public record Classification(Set<String> accepted, Set<String> declined) {
    }

    /** 简单的控制台日志器(引擎是静态工具类;宿主可自行包装)。 */
    private static final class LoggerServiceHolder {
        final Logger logger = new dev.cordis.core.runtime.LoggerService().logger("hmr");
    }

    /**
     * 阶段 1:模块分类。从变更文件的引用出发反复推导,直到不再变化:
     * 引用了“要换模块”的跟着换;引用到的全都不能换的自己也不能换;
     * 因为循环引用一直定不下来的,一律按“不能换”处理(保守策略)。
     */
    public static Classification classify(Set<String> stashed, Set<String> externals, ModuleGraph graph) {
        Set<String> accepted = new HashSet<>(stashed);
        Set<String> declined = new HashSet<>(externals);
        Set<String> pending = new LinkedHashSet<>();
        for (String url : stashed) {
            pending.addAll(minus(graph.imports(url), accepted, declined));
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String url = it.next();
                Set<String> imports = graph.imports(url);
                if (!intersection(imports, accepted).isEmpty()) {
                    accepted.add(url);
                    it.remove();
                    progress = true;
                } else if (declined.containsAll(imports)) {
                    declined.add(url);
                    it.remove();
                    progress = true;
                } else {
                    pending.addAll(minus(imports, accepted, declined));
                }
            }
        }
        declined.addAll(pending);
        return new Classification(accepted, declined);
    }

    /** 收集一个模块传递引用到的所有模块;declined 里的当作边界,不再往里走。 */
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
     * 阶段 2:陈旧检测。一个插件只要依赖树里出现过“要换的模块”,就算陈旧,
     * 需要重装;树里其余陈旧模块一并作废。
     *
     * @param entryUrls 所有已挂载 entry 的 url
     * @return 陈旧 entry 的 url 集合
     */
    public static Set<String> detectStale(Collection<String> entryUrls, Set<String> accepted,
                                          Set<String> declined, ModuleGraph graph) {
        Set<String> staleEntries = new LinkedHashSet<>();
        Set<String> acceptedMutable = new HashSet<>(accepted);
        for (String url : entryUrls) {
            Set<String> tree = dependencies(url, declined, graph);
            if (!intersection(tree, acceptedMutable).isEmpty()) {
                acceptedMutable.addAll(tree);
                staleEntries.add(url);
            }
        }
        return staleEntries;
    }

    /**
     * 阶段 3:事务性重载。逐个“卸旧 fiber、装新模块”;
     * 中途任何一个失败,就把已经换过的全部从旧组件重建回去,再把错误抛出——
     * 保证系统绝不会停在“换了一半”的状态。
     *
     * @param loader      管理这些 entry 的加载器
     * @param staleIds    要重载的 entry id
     * @param fresh       url → 新编译的插件;缺省(或没有对应 url)就重新走解析器
     * @param backupStore 可选,接收每个 id 换下来的旧插件(备份)
     */
    public static void reload(ComponentLoader loader, Collection<String> staleIds,
                              Map<String, Plugin> fresh, Map<String, Plugin> backupStore) throws Exception {
        Map<String, Plugin> backup = new HashMap<>();
        try {
            for (String id : staleIds) {
                ComponentLoader.Mounted m = loader.mounted(id);
                if (m == null || m.fiber == null) continue;
                backup.put(id, m.plugin);
                Plugin replacement = m.entry != null && fresh != null ? fresh.get(m.entry.url) : null;
                loader.remount(id, replacement);
            }
        } catch (Exception error) {
            log.error("hmr reload failed, rolling back: " + error);
            for (Map.Entry<String, Plugin> e : backup.entrySet()) {
                try {
                    loader.remount(e.getKey(), e.getValue());
                } catch (Exception rollbackError) {
                    log.error("rollback of " + e.getKey() + " failed: " + rollbackError);
                }
            }
            if (backupStore != null) backupStore.putAll(backup);
            throw error;
        }
        if (backupStore != null) backupStore.putAll(backup);
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

    /** 辅助方法:把陈旧的 url 对应回 loader 里的 entry id。 */
    public static List<String> idsForUrls(ComponentLoader loader, Set<String> staleUrls) {
        List<String> ids = new ArrayList<>();
        for (String id : loader.ids()) {
            ComponentLoader.Mounted m = loader.mounted(id);
            if (m != null && m.entry != null && staleUrls.contains(m.entry.url)) ids.add(id);
        }
        return ids;
    }
}
