# cordis-java

Java 25 实现的 **Cordis** —— 时空可组合性(spatiotemporal composability)元框架,对应论文
*A Programming Paradigm for Spatiotemporal Composability*(Shi, Zhang, Cui;北大 & DeepSeek-AI)
第 3–5 章的运行时模型,并以 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
仓库 vendor 的 `vendor/cordis`(cordis v4)源码为语义参照。

## 两个维度

| 维度 | 论文机制 | 本实现 |
|---|---|---|
| 时间可组合性 | 可回退效应(revertible effects):每个上下文变换携带运行时持有的逆变换,累加器按 LIFO 恢复(§3.1) | `Disposable`(撤销动作/逆变换)+ `Fiber.effect`(Algorithm 1:`execute` 折叠逆变换,`armed` 自处置,父级组合) |
| 空间可组合性 | 响应式余效应(reactive coeffects):每次上下文变化按组件规范分类为激活/去激活/中性(§3.2) | `inject` 依赖声明 + `ReflectService.notify` 变更广播 + `Fiber.refresh`(目标视图 = 提供者 uid 摘要) |
| 统一 | 上下文范式:效应与余效应经同一上下文中介(§3.3) | `Context`:`effect/get/set/provide/isolate/intercept/plugin` |

## 术语对照表

本项目的文档、注释与讲解统一使用下表的中文名;代码标识符保留英文。

| 英文 | 中文名 | 一句话释义 |
|---|---|---|
| Plugin | 插件 | 组件的声明形式:inject 声明依赖,apply 是主体 |
| Context | 上下文 | 组件与外界打交道的唯一通道 |
| Fiber | 运行实例(fiber) | 插件的一次运行:一个插件装一次,就是一个 |
| Effect | 效应 | 组件对外界做的事,登记时须同时说清怎么撤销 |
| Coeffect | 余效应 | 环境对组件的供给,通俗理解即"依赖" |
| Disposable | 撤销动作 | 效应的逆变换,卸载时自动执行 |
| EffectCallback | 效应回调(效果体) | 效应体:执行并交出"以后怎么撤销" |
| EffectMeta | 效应元数据 | 效应名 + 嵌套子效应,组成诊断树 |
| inject | 注入(依赖声明) | 声明"我要用什么服务" |
| provide / get | 提供 / 获取 | 对外注册服务 / 受声明约束地取用 |
| ReflectService | 服务仓库 | 全应用服务绑定的存放地 + 变更广播 |
| Impl | 服务绑定 | 某服务名在某隔离域下的具体实现 |
| realm / RealmKey | 隔离域 / 隔离域门牌 | 同名服务分域各存一份绑定;同门牌即共用 |
| isolate / intercept / extend | 隔离 / 拦截 / 派生 | 子上下文的三种派生:独立绑定 / 叠加配置(权限收缩) / 普通派生 |
| store | 依赖快照 | 启动那一刻,各依赖分别由谁提供 |
| target | 目标视图 | "我的每个依赖现在由谁提供"的实时摘要 |
| epoch | 世代 | 提供者换一拨,世代就推进 |
| records | 撤销动作栈 | 登记过的全部效应,卸载时后进先出逆序执行 |
| PENDING / LOADING / ACTIVE | 待命 / 加载中 / 运行中 | 六态生命周期前三态(待命是正常状态,不是错误) |
| FAILED / UNLOADING / DISPOSED | 启动失败 / 卸载中 / 已销毁 | 后三态 |
| INACTIVE_ACCESS / UNDECLARED_ACCESS | 未激活访问 / 未声明访问(越权) | get 能力检查的两类错误 |
| notify | 变更广播 | 绑定一变,通知所有声明的实例重新检查依赖 |
| reconcile | 调和 | 按 id 对账:消失的卸载、新增的挂载、存活的按字段最小扰动 |
| Entry / Mounted | 配置条目 / 已挂载记录 | 装载清单里的一行声明 / 它对应的运行中实例 |
| HMR (Hot Module Replacement) | 热更新(热模块替换) | 文件改了原地换血,进程不重启 |
| ModuleGraph | 模块依赖图 | 谁引用谁,供热更新推导影响范围 |
| emit / parallel / bail / waterfall | 顺序 / 并行 / 短路 / 瀑布派发 | 事件总线的四种派发模式 |

## 模块

```
cordis-core
├── dev.cordis.core             根包:各处共用的基础类型
│   ├── Plugin / ConfigSchema   插件定义与配置校验器
│   └── CordisException / ValidationException  统一异常(带稳定错误码)
├── dev.cordis.core.effect      可回退效应(§3.1,算法 1):怎么登记、怎么撤销
│   ├── Disposable              撤销动作(效应的"逆变换")
│   ├── EffectCallback          效应回调(效果体):返回单个撤销动作;返回 Iterable 即多阶段(阶段间可被打断)
│   └── EffectMeta              效应元数据(诊断树)
├── dev.cordis.core.coeffect    余效应/服务依赖(§3.2,算法 2/3):需要什么、由谁提供
│   ├── ReflectService          服务仓库(按隔离域门牌键控)+ notify 变更广播
│   ├── Impl                    一条服务绑定(提供者 fiber + 值 + 可用性检查)
│   ├── RealmKey                隔离域门牌:同名服务在不同域各有一条绑定
│   └── Service                 服务基类:构造即 provide,随 fiber 卸载自动注销
├── dev.cordis.core.runtime     运行时(§4/§5.1,算法 4/5/6):把上面两块粘成会自转的机器
│   ├── Context                 一等上下文:服务解析(算法 6)、isolate 隔离/intercept 拦截派生、plugin() 挂载
│   ├── Fiber                   运行实例与六态生命周期机(待命 PENDING → 加载中 LOADING → 运行中 ACTIVE;
│   │                           启动失败 FAILED;卸载中 UNLOADING → 已销毁 DISPOSED;惰性链式转换)
│   ├── FiberState              六态枚举
│   ├── RegistryService         插件注册表:plugin() 即 O-Insert,其逆即 O-Retire(Definition 52)
│   ├── PluginRuntime           插件运行时记录(同一插件所有运行实例的共用档案)
│   ├── EventsService           事件总线:顺序派发 emit / 并行派发 parallel / 短路派发 bail /
│   │                           瀑布派发 waterfall(监听器是可回退效应)
│   └── Logger(+Service)        命名日志门面
├── dev.cordis.loader           组件加载器(声明式,§5.2.1)
│   ├── Entry                   配置条目:id/url/isolate/intercept/config/disabled(Definition 81)
│   ├── PluginResolver          插件解析器:url → 插件(模块引入/撤出交给宿主)
│   └── ComponentLoader         按 id 调和;逐字段最小扰动分发;正确性由 Theorem 80 保证
└── dev.cordis.hmr              热更新引擎(HMR,热模块替换;§5.2.2,算法 8–10)
    ├── HmrEngine.classify      阶段 1:模块分类不动点(可热换 accepted / 拒热换 declined;环上未决默认拒热换)
    ├── HmrEngine.detectStale   阶段 2:陈旧配置条目检测(依赖树 ∩ accepted)
    └── HmrEngine.reload        阶段 3:事务性重载(失败即从上一组件回滚,无半重载状态)

cordis-example            插件实例与端到端演示(database/user/greeting + 隔离/拦截/HMR)
```

## 快速开始

```bash
mvn test                                  # 30 个单元测试
mvn -pl cordis-example exec:java          # 端到端演示(先 mvn install)
```

### 作为库使用

```java
Context root = Context.createRoot();

// 插件:inject 即依赖声明(余效应规范 d),apply 即效应函数 e
Plugin database = Plugin.of("database", List.of(), (ctx, cfg) -> {
    ctx.effect(cb -> {           // 可回退效应:打开连接
        log.info("opened");
        return (Disposable) () -> log.info("closed");   // 撤销动作(逆变换)
    }, "connection");
    ctx.provide("db", new DbService(ctx));              // 提供(p)
    return null;                                        // 已登记的效应自动回滚
});

Plugin users = Plugin.of("users", List.of("db"), (ctx, cfg) -> {
    DbService db = ctx.get("db");                       // 能力受 inject 约束
    return null;                                        // db 消失时整段自动回滚
});

Fiber f = root.plugin(users);        // 待命(PENDING):db 未提供,安静等待
root.plugin(database).await();       // db 运行中(ACTIVE) → users 被通知 → 重载 → ACTIVE
f.await();
```

### 声明式加载

```java
ComponentLoader loader = new ComponentLoader(root, url -> resolveModule(url));
loader.load(List.of(
    Entry.of("users", "plugin-users").build(),          // 先挂载也安全:待命等提供者
    Entry.of("database", "plugin-database").build()));
loader.load(List.of(                                    // 变更调和(按 id 差分)
    Entry.of("users", "plugin-users").build()));        // 移除 database → users 自动去激活
```

### 隔离与拦截

```java
Context tenantA = root.isolate("db", "db@tenant-a");   // 同名服务在不同隔离域独立解析
Context policed = tenantA.intercept("db", Map.of("readOnly", true));  // 拦截:编排者收缩权限
```

### 热更新(HMR)

```java
ModuleGraph graph = url -> importsOf(url);
var cls = HmrEngine.classify(Set.of("edited-module"), Set.of(/*externals*/), graph);
var stale = HmrEngine.detectStale(entryUrls, cls.accepted(), cls.declined(), graph);
HmrEngine.reload(loader, HmrEngine.idsForUrls(loader, stale), freshPlugins, backup);
// 任一模块失败 → 全部从上一组件回滚,系统不进入半重载状态
```

## 与 cordis(TS)的对应

| cordis v4 | cordis-java |
|---|---|
| `ctx`(Proxy + prototype 链) | `Context`(parent 链 + 显式 isolate/intercept 表) |
| `ctx.effect(cb)` / 生成器效应 | `ctx.effect(EffectCallback)`,惰性 `Iterable<Disposable>` 为多阶段(stage 边界即 Maybe(ℑ) 延续) |
| `fiber.epoch` / `fiber.target` | `Fiber.epoch` 世代 / `Fiber.target` 目标视图(提供者 uid 摘要;覆写绑定不触发重载) |
| `fiber.inertia`(Promise) | `Fiber.inertia`(同步 Runnable 链;命令式宿主的就地实现,Definition 23) |
| unload 前等待被通知的依赖 | ACTIVE→UNLOADING 状态变化同步通知,依赖方先于提供者的撤销动作完成卸载(L-Unload guard) |
| `@cordisjs/loader` 按 id 调和 | `ComponentLoader.reconcile` |
| `@cordisjs/hmr`(Loader 内省) | `HmrEngine` + 宿主提供的 `ModuleGraph`/`PluginResolver` |

## 设计取舍

- **同步转换**:cordis 基于 JS 事件循环的异步惯性;Java 版在调用线程上同步完成 reload/unload 链,
  语义等价(转换一旦开始必然完成、完成后才响应新的目标变化),且顺序更易推理。异步效应可在
  `Disposable` 内自行对接 `CompletableFuture`。
- **无 Proxy**:Java 无属性拦截原语,`ctx.get(key)` 承担 Algorithm 6 的按需能力检查
  (未声明访问(越权)抛 `UNDECLARED_ACCESS`;已声明未激活访问抛 `INACTIVE_ACCESS`)。
- **模块解析**:JVM 无 Node 式模块注册表,`PluginResolver` 把"引入/撤出模块"交给宿主
  (注册表、反射、外部文件均可);HMR 的 `fresh` 参数即"重编译后的新模块"。
- **隔离域的门牌**:`isolate(name)` 生成私有隔离域;`isolate(name, label)` 以对象相等性 Join
  (同 JS Symbol 语义),字符串门牌经 `RealmKey.shared` 全局共享。

## 测试

`cordis-core/src/test` 共 6 个测试类、30 个用例,逐一锁住行为契约:LIFO 效应回滚、撤销动作单次处置、
多阶段效应、待命→运行中的响应式加载、提供者撤离的 guard 顺序(消费者先卸载)、提供者替换触发重载、
重复 provide 拒绝、restart/update、未声明/未激活访问错误、隔离域隔离与通知不过界、同门牌 Join、
拦截配置合并、加载器逐字段调和、配置校验失败路径、HMR 分类不动点/环默认拒热换/陈旧检测/事务性回滚。
对学习者而言,这 30 个用例同时也是一份**可执行的行为规格说明书**。
