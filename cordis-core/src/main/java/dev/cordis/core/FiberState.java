package dev.cordis.core;

/**
 * 一个插件实例(fiber)的生命周期状态。
 *
 * <ul>
 *   <li>{@code PENDING} —— 等依赖:需要的服务还没就绪,安静地等(这是正常状态,不是错误);</li>
 *   <li>{@code LOADING} —— 正在启动(apply 正在跑);</li>
 *   <li>{@code ACTIVE} —— 已加载并对外提供服务,唯一能被别人依赖的状态;</li>
 *   <li>{@code FAILED} —— 启动失败(apply 或配置校验抛了异常),错误只记在自己身上,不影响别人;</li>
 *   <li>{@code UNLOADING} —— 正在卸载:撤销动作正按逆序执行;</li>
 *   <li>{@code DISPOSED} —— 已被移除,不能再用。</li>
 * </ul>
 */
public enum FiberState {
    PENDING,
    LOADING,
    ACTIVE,
    FAILED,
    DISPOSED,
    UNLOADING
}
