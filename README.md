# cordis-java

Java 25 实现的 **Cordis** —— 时空可组合性(spatiotemporal composability)元框架,对应论文
*A Programming Paradigm for Spatiotemporal Composability*(Shi, Zhang, Cui;北大 & DeepSeek-AI)
第 3–5 章的运行时模型,并以 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
仓库 vendor 的 `vendor/cordis`(cordis v4)源码为语义参照。

## 两个维度

| 维度 | 论文机制 | 本实现 |
|---|---|---|
| 时间可组合性 | 可回退效应(revertible effects):每个上下文变换携带运行时持有的逆变换,累加器按 LIFO 恢复(§3.1) | `Disposable`(逆变换)+ `Fiber.effect`(Algorithm 1:`execute` 折叠逆变换,`armed` 自处置,父级组合) |
| 空间可组合性 | 响应式余效应(reactive coeffects):每次上下文变化按组件规范分类为激活/去激活/中性(§3.2) | `inject` 声明 + `ReflectService.notify` + `Fiber.refresh`(target 视图 = 提供者 uid 摘要) |
| 统一 | 上下文范式:效应与余效应经同一上下文中介(§3.3) | `Context`:`effect/get/set/provide/isolate/intercept/plugin` |

## 模块

```
cordis-core
├── dev.cordis.core             根包:各处共用的基础类型
│   ├── Plugin / ConfigSchema   插件定义与配置校验器
│   └── CordisException / ValidationException  统一异常(带稳定错误码)
├── dev.cordis.core.effect      可回退效应(§3.1,算法 1):怎么登记、怎么撤销
│   ├── Disposable              撤销动作(效应的"逆变换")
│   ├── EffectCallback          效果体:返回单个撤销动作;返回 Iterable 即多阶段(阶段间可被打断)
│   └── EffectMeta              效果诊断树
├── dev.cordis.core.coeffect    余效应/服务依赖(§3.2,算法 2/3):需要什么、由谁提供
│   ├── ReflectService          全局服务仓库(按 realm 键控)+ notify 变更广播
│   ├── Impl                    一条服务绑定(提供者 fiber + 值 + 可用性检查)
│   ├── RealmKey                隔离域标识:同名服务在不同域各有一条绑定
│   └── Service                 服务基类:构造即 provide,随 fiber 卸载自动注销
├── dev.cordis.core.runtime     运行时(§4/§5.1,算法 4/5/6):把上面两块粘成会自转的机器
│   ├── Context                 一等上下文:服务解析(算法 6)、isolate/intercept 派生、plugin() 挂载
│   ├── Fiber                   生命周期状态机(PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED,惰性链式)
│   ├── FiberState              状态枚举
│   ├── RegistryService         插件注册表:plugin() 即 O-Insert,其逆即 O-Retire(Definition 52)
│   ├── PluginRuntime           同一插件共享的运行时记录
│   ├── EventsService           事件总线(on/emit/bail/waterfall,监听器是可回退效应)
│   └── Logger(+Service)        命名日志门面
├── dev.cordis.loader           声明式组件加载器(§5.2.1)
│   ├── Entry                   配置项:id/url/isolate/intercept/config/disabled(Definition 81)
│   ├── PluginResolver          url → 插件(模块引入/撤出交给宿主)
│   └── ComponentLoader         按 id 调和;逐字段分发;正确性由 Theorem 80 保证
└── dev.cordis.hmr              热模块替换(§5.2.2,算法 8–10)
    ├── HmrEngine.classify      阶段 1:模块分类不动点(环上未决默认 declined)
    ├── HmrEngine.detectStale   阶段 2:陈旧 entry 检测(依赖树 ∩ accepted)
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

// 插件:声明 inject(余效应规范 d),apply 即效应函数 e
Plugin database = Plugin.of("database", List.of(), (ctx, cfg) -> {
    ctx.effect(cb -> {           // 可回退效应:打开连接
        log.info("opened");
        return (Disposable) () -> log.info("closed");   // 逆变换
    }, "connection");
    ctx.provide("db", new DbService(ctx));              // 提供(p)
    return null;                                        // 已登记的效应自动回滚
});

Plugin users = Plugin.of("users", List.of("db"), (ctx, cfg) -> {
    DbService db = ctx.get("db");                       // 能力受 inject 约束
    return null;                                        // db 消失时整段自动回滚
});

Fiber f = root.plugin(users);        // PENDING:db 未提供
root.plugin(database).await();       // db ACTIVE → users 被通知 → 重载 → ACTIVE
f.await();
```

### 声明式加载

```java
ComponentLoader loader = new ComponentLoader(root, url -> resolveModule(url));
loader.load(List.of(
    Entry.of("users", "plugin-users").build(),          // 先挂载也安全:等待提供者
    Entry.of("database", "plugin-database").build()));
loader.load(List.of(                                    // 变更调和(按 id 差分)
    Entry.of("users", "plugin-users").build()));        // 移除 database → users 自动去激活
```

### 隔离与拦截

```java
Context tenantA = root.isolate("db", "db@tenant-a");   // 同名服务在不同 realm 独立解析
Context policed = tenantA.intercept("db", Map.of("readOnly", true));  // 编排者收缩权限
```

### HMR

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
| `fiber.epoch` / `fiber.target` | `Fiber.epoch` / `Fiber.target`(提供者 uid 摘要;覆写绑定不触发重载) |
| `fiber.inertia`(Promise) | `Fiber.inertia`(同步 Runnable 链;命令式宿主的就地实现,Definition 23) |
| unload 前等待被通知的依赖 | ACTIVE→UNLOADING 状态变化同步通知,依赖先于提供者的逆变换完成卸载(L-Unload guard) |
| `@cordisjs/loader` 按 id 调和 | `ComponentLoader.reconcile` |
| `@cordisjs/hmr`(Loader 内省) | `HmrEngine` + 宿主提供的 `ModuleGraph`/`PluginResolver` |

## 设计取舍

- **同步转换**:cordis 基于 JS 事件循环的异步惯性;Java 版在调用线程上同步完成 reload/unload 链,
  语义等价(转换一旦开始必然完成、完成后才响应新的目标变化),且顺序更易推理。异步效应可在
  `Disposable` 内自行对接 `CompletableFuture`。
- **无 Proxy**:Java 无属性拦截原语,`ctx.get(key)` 承担 Algorithm 6 的按需能力检查
  (未声明的访问抛 `UNDECLARED_ACCESS`;已声明未加载抛 `INACTIVE_ACCESS`)。
- **模块解析**:JVM 无 Node 式模块注册表,`PluginResolver` 把"引入/撤出模块"交给宿主
  (注册表、反射、外部文件均可);HMR 的 `fresh` 参数即"重编译后的新模块"。
- **隔离域的标签**:`isolate(name)` 生成私有 realm;`isolate(name, label)` 以对象相等性 Join
  (同 JS Symbol 语义),字符串标签经 `RealmKey.shared` 全局共享。

## 测试

`cordis-core/src/test` 覆盖:LIFO 效应回滚、单次处置、多阶段效应、PENDING→ACTIVE 反应式加载、
提供者撤离的 guard 顺序(消费者先卸载)、提供者替换触发重载、重复 provide 拒绝、restart/update、
未声明/未激活访问错误、realm 隔离与通知不过界、同标签 Join、intercept 合并、加载器逐字段调和、
schema 校验失败路径、HMR 分类不动点/环默认拒绝/陈旧检测/事务回滚,共 30 例。
