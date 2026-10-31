/**
 * 运行时(论文 §4 / §5.1,算法 4/5/6)——把 effect 与 coeffect 粘成一台会自己运转的机器。
 *
 * <ul>
 *   <li>{@link dev.cordis.core.runtime.Context} —— 组件与外界打交道的唯一通道:
 *       登记效果、提供/获取服务、派生隔离与拦截、加载插件;</li>
 *   <li>{@link dev.cordis.core.runtime.Fiber} —— 插件的一次运行实例,带惰性生命周期状态机
 *       (转换一旦开始必先完成,再按最新依赖状态链入下一个转换);</li>
 *   <li>{@link dev.cordis.core.runtime.RegistryService} —— 插件注册表,管理每个插件的 fiber;</li>
 *   <li>{@link dev.cordis.core.runtime.EventsService} —— 事件总线,监听器随 fiber 卸载自动移除;</li>
 *   <li>{@link dev.cordis.core.runtime.Logger} / {@link dev.cordis.core.runtime.LoggerService} —— 日志。</li>
 * </ul>
 */
package dev.cordis.core.runtime;
