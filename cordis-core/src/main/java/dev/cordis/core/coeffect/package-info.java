/**
 * 余效应 / 服务依赖(论文 §3.2,算法 2/3)——组件“需要什么、由谁提供”。
 *
 * <p>{@link dev.cordis.core.coeffect.ReflectService} 是全局服务仓库:
 * 绑定按 {@link dev.cordis.core.coeffect.RealmKey 隔离域} 分 key 存放
 * (同名服务在不同域各有一条绑定,多租户互不干扰)。
 * 绑定一有变化就广播给所有声明了该服务的 fiber——依赖齐了自动启动,缺了自动卸载,
 * 这就是“响应式”的来源。{@link dev.cordis.core.coeffect.Impl} 是一条绑定
 * (提供者 fiber + 值);{@link dev.cordis.core.coeffect.Service} 是
 * “把实例挂到 ctx 上”的服务基类。</p>
 */
package dev.cordis.core.coeffect;
