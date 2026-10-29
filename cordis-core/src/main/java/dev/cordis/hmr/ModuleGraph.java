package dev.cordis.hmr;

import java.util.Set;

/**
 * 模块依赖图:HMR 引擎靠它回答“某个模块引用了哪些其他模块”。
 * 由宿主提供(构建索引、类引用扫描,或手写的映射都行)。
 */
@FunctionalInterface
public interface ModuleGraph {

    /** url 直接引用的模块;叶子模块返回空集合。 */
    Set<String> imports(String url);
}
