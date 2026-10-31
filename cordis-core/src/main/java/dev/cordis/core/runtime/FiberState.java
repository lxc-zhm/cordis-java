package dev.cordis.core.runtime;

/**
 * 运行实例(fiber)的六态生命周期状态。
 *
 * <ul>
 *   <li>{@code PENDING} —— 待命:需要的服务还没就绪,安静地等(这是正常状态,不是错误);</li>
 *   <li>{@code LOADING} —— 加载中:插件主体(apply)正在执行;</li>
 *   <li>{@code ACTIVE} —— 运行中:已就绪并对外提供服务,唯一能被别人依赖的状态;</li>
 *   <li>{@code FAILED} —— 启动失败(apply 或配置校验抛了异常),错误只记在自己身上,不影响别人;</li>
 *   <li>{@code UNLOADING} —— 卸载中:撤销动作正按后进先出的逆序执行;</li>
 *   <li>{@code DISPOSED} —— 已销毁:彻底移除,不能再用。</li>
 * </ul>
 */
public enum FiberState {
    /** 待命:等依赖,所需服务未就绪,安静地等(正常状态)。 */
    PENDING,
    /** 加载中:apply 正在执行。 */
    LOADING,
    /** 运行中:已加载并对外提供服务,唯一能被别人依赖的状态。 */
    ACTIVE,
    /** 启动失败:错误只记在自己身上,不影响别人。 */
    FAILED,
    /** 已销毁:已被移除,不能再用。 */
    DISPOSED,
    /** 卸载中:撤销动作按逆序执行。 */
    UNLOADING
}
