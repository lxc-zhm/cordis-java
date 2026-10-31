/**
 * 可回退效应(论文 §3.1,算法 1)——本框架的“撤销机器”。
 *
 * <p>一句话:组件对外界做的每个改动,登记时就要同时说清“以后怎么撤销”。
 * 登记入口是 {@code Context.effect(cb)},cb 立即执行并返回撤销动作;
 * 卸载时按“后登记的先撤销”逆序执行,现场被完整还原。</p>
 *
 * <ul>
 *   <li>{@link dev.cordis.core.effect.Disposable} —— 一个撤销动作(效应的“逆变换”);</li>
 *   <li>{@link dev.cordis.core.effect.EffectCallback} —— 效果体:返回单个撤销动作,
 *       或一组(多阶段,阶段之间可以被打断、只回滚已完成部分);</li>
 *   <li>{@link dev.cordis.core.effect.EffectMeta} —— 效果的诊断树,排查问题用。</li>
 * </ul>
 */
package dev.cordis.core.effect;
