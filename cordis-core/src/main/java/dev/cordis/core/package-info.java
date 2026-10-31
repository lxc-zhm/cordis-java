/**
 * cordis-java 核心包:对应论文 §3–§5.1 的运行时模型。
 *
 * <p>按概念分三个子包,建议按这个顺序阅读:</p>
 * <ul>
 *   <li>{@link dev.cordis.core.effect effect} —— 可回退效应:组件做的事怎么登记、怎么撤销;</li>
 *   <li>{@link dev.cordis.core.coeffect coeffect} —— 余效应(服务依赖):依赖存哪里、变化了怎么通知;</li>
 *   <li>{@link dev.cordis.core.runtime runtime} —— 运行时:上下文、fiber 生命周期、插件注册表、事件总线,
 *       把前两块粘成一台会自己运转的机器。</li>
 * </ul>
 * <p>根包只放各处共用的基础类型:插件定义({@link dev.cordis.core.Plugin}、
 * {@link dev.cordis.core.ConfigSchema})和统一异常({@link dev.cordis.core.CordisException}、
 * {@link dev.cordis.core.ValidationException})。</p>
 */
package dev.cordis.core;
