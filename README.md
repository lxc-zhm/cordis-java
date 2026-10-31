# cordis-java

Java 25 版 Cordis：可回退效应、响应式依赖、上下文隔离与拦截、组件生命周期、声明式配置树和源码热更新。
以 *A Programming Paradigm for Spatiotemporal Composability* 第 3–5 章，以及
[deepseek-harness 的 Cordis 实现](https://github.com/deepseek-ai/deepseek-harness/tree/ce7073d51254c26cd63b22a60835b8f948deb447/vendor/cordis) 为参照。

这是 Java API 的实现，不提供 TypeScript 插件的二进制兼容。下面区分已实现行为和 JVM 上的适配边界。

## 构建与运行

```sh
mvn test
mvn package
# 电商订单业务示例：库存、沙箱支付、仓配、隔离与促销热更新
mvn install
mvn -pl cordis-example exec:java
# 真实 Java 源码 + YAML 配置监听；编辑 HelloPlugin.java 或 cordis.yaml 可观察更新
mvn -pl cordis-core org.codehaus.mojo:exec-maven-plugin:3.2.0:java \
  -Dexec.mainClass=dev.cordis.loader.CordisApplication \
  -Dexec.args="examples/hot-reload/cordis.yaml examples/hot-reload/plugins --watch"
```

电商示例的业务流程、插件职责与数据文件说明见 [cordis-example/README.md](cordis-example/README.md)。

需要 JDK 25。运行时除 JDK 外仅依赖 SnakeYAML 2.5，用于安全读取 YAML/JSON；编译器关闭注解处理。

## 效应与生命周期

```java
Context root = Context.createRoot();
Plugin database = Plugin.of("database", List.of(), (ctx, config) -> {
    ctx.provide("db", openDatabase());
    return (Disposable) () -> closeDatabase();
});
Fiber consumer = root.plugin(Plugin.of("users", List.of("db"), (ctx, config) -> {
    Object db = ctx.get("db");
    return (Disposable) () -> releaseUserResources(db);
}));
root.plugin(database).await(); // consumer 自动从 PENDING 进入 ACTIVE
root.fiber().dispose();       // 等消费者撤销完，再撤销提供者，最后清空根效应
```

`Plugin.apply` 和 `ctx.effect` 接受 `null`、`Disposable`、`Iterable<Disposable>`、
`CompletionStage`（可解析为上述结果）或 `AsyncEffect`。`AsyncDisposable` 的异步完成纳入生命周期：

```java
Plugin async = Plugin.of("async", List.of(), (ctx, config) ->
    connectAsync().thenApply(connection ->
        (AsyncDisposable) connection::closeAsync));
Fiber fiber = root.plugin(async);
fiber.awaitAsync().thenRun(() -> useApplication());
fiber.disposeAsync().toCompletableFuture().join();
```

`AsyncEffect.next()` 返回 `CompletionStage<AsyncEffect.Step>`。每次推进迭代前检查目标；
已经开始的阶段会完成，其返回的逆操作始终被保留，然后停止后续阶段并回退。
效应在执行用户代码前就登记到所有者，避免重入卸载遗漏资源。逆操作按 LIFO 执行，单项失败不阻止其余清理。

六态为 `PENDING / LOADING / ACTIVE / FAILED / UNLOADING / DISPOSED`。
`await()` 等当前转换稳定；`PENDING` 表示依赖未就绪，等待稳定不等于等待将来某天激活。
异步回调可从其他执行器完成；状态修改由根上下文串行化。生命周期回调内使用
`awaitAsync / updateAsync / restartAsync / disposeAsync`，不要阻塞等待自身或依赖自己的任务。
`dispose()` 在外部调用时等待清理；在运行时回调中发起清理，由所有者等待完成。

`ctx.get(name)` 依据声明和已提交视图检查能力；撤销期间仍可读取原依赖。
`ctx.getOrNull(name)` 是显式的非受限反射查询。`ctx.set` 只允许提供者更新自己的值。
每次 provision 都有独立身份，同一提供者撤回后重新提供也能触发消费者重载。

## 隔离与服务拦截

```java
Context tenant = root.isolate("db", "tenant-a");
Context restricted = tenant.intercept("db", Map.of("readOnly", true));
```

不同服务使用相同字符串标签不会串域；相同服务与标签共享绑定。
`isolate(name)` 创建私有域，显式传入 `RealmKey` 可按对象身份共享。
配置合并从根到叶，且不修改祖先的 Map。

Java 无法用 JDK Proxy 拦截任意具体类的属性访问，因此服务通过 `ContextualService<T>` 返回调用者视图。
视图必须共享底层状态，并在方法调用时解析当前上下文的策略。具体类示例见 `cordis-example` 的 `OrderService`；接口服务可以使用：

```java
root.provide("db", Service.bind(Database.class, (caller, call) -> {
    Object policy = caller.resolveIntercept("db");
    // 根据 policy 校验权限，再调用共享状态上的对应方法
    return dispatchDatabaseCall(policy, call);
}));
```

框架不把 `readOnly` 这样的任意元数据自动解释成所有服务的权限；其解释由服务实现。
`Service` 基类负责自动注册和可用性检查。需要调用者策略的服务应实现上述视图契约。

## 声明式配置树

`ComponentLoader` 按条目 id 调和，检查重复 id 后才修改运行中的树。
模块导入和异步启动不强制配置顺序；`reconcileAsync` 返回本次调和的完成阶段。

```java
try (ComponentLoader loader = ConfigFile.open(root, resolver, Path.of("cordis.yaml"))) {
    loader.load(List.of(Entry.of("db", "DatabasePlugin.java").config(config).build()));
    loader.mounted("db").fiber.update(newConfig); // 配置回写
}
```

```yaml
- id: tenants
  url: group
  config:
    - id: database
      url: DatabasePlugin.java
      isolate:
        db: tenant-a
      intercept:
        db:
          readOnly: true
      config:
        connection: demo
- id: additional
  url: include
  config: nested/extra.yaml
```

`ConfigFile.open` 自动启用 `group / @cordis/group`、`include / @cordis/include`。
include 路径相对所在文件解析，检测循环引用；子树资源受父 fiber 管理。
插件调用 `fiber.update()` 或自行 dispose 时，会回写 config/disabled；嵌套组更新递归回写到对应文件。
写文件使用同目录临时文件和原子替换；JSON 输出为 JSON，YAML 使用安全构造器并拒绝重复键。

字段更新策略：id/url 改变重建，disabled 管理启停，config 交给组件处理；group 对子 id 递归差分。
intercept 原地更新，不重启插件；isolate 原地迁移绑定，先检查冲突，再通知消费者重新解析。
私有域在同一条目存活期间保持身份；删除条目后释放其域引用。
`sourceLoader.move(id, targetLoader)` 可在同一应用的组间迁移条目，保留 fiber、私有域与资源，并转移卸载所有权。

`ConfigWatcher` 监听根文件与动态 include 文件，防抖后调和；语法错误保留原树并发出
`loader/config-error`，成功发出 `loader/config-reloaded`。
配置应使用 JSON/YAML 数据值；任意 Java 对象及手工 RealmKey 无法持久化。

## JVM 源码热更新

```java
try (JavaModuleResolver modules = new JavaModuleResolver(Path.of("plugins"));
     ComponentLoader loader = ConfigFile.open(root, modules, Path.of("cordis.yaml"));
     HotReloadService hmr = new HotReloadService(loader, modules, Duration.ofMillis(150))) {
    // 修改 .java 文件会触发编译、替换或失败回滚；也可调用 hmr.reloadNow()
}
```

`JavaModuleResolver` 编译源码目录，每一代用独立 ClassLoader 加载，Cordis API 由宿主共享。
先编译和解析全部候选插件，再卸载旧实例；候选启动失败会先清理候选，恢复旧插件与解析器缓存。
语法错误发生在卸载前，旧实例保持运行。HMR 覆盖当前配置树中的源码插件。
监听由父上下文持有，关闭应用时自动停止；完整应用入口为 `CordisApplication.open`。

`HmrEngine` 同时保留论文算法 8–10 对应的模块分类、陈旧条目检测和手工插件事务替换 API。
源码依赖图采用保守引用分析。为避免同一源码目录中的类跨代混用，文件热更新重载该目录中
**所有已挂载的源码插件**，并非只替换文本上直接变化的条目。
可配置 externals；这些文件变化发出 `hmr/restart-required`，由宿主决定如何重启进程。

适配范围：当前源码模块为 `.java`、无参构造的 `Plugin` 实现。依赖来自宿主 classpath；
需要额外依赖时可显式给解析器传 classpath。不提供 Node ESM/CJS 缓存兼容、任意 JAR 动态下载、
运行中对象的字段迁移或外部数据库事务回滚。旧 ClassLoader/编译产物保留到解析器 close，
以支持旧代码回滚及延迟类加载；长时间开发会话应定期重建应用。

## 事件、日志与定时器

事件支持上下文范围、全局监听器（仍归注册者所有）、once、emit、parallelAsync 和 serialAsync。
parallelAsync 先发起所有回调，再等待全部完成；serialAsync 按顺序等待并在非 null/false 结果处短路。
`waterfall` 保留显式 `Supplier<Object> next` 的洋葱式调用协议。
日志支持命名 Logger 和可增删的 exporter；`ctx.exporter` 让输出端随注册者自动移除。
`ctx.accessor` 提供计算属性，`ctx.mixin(service, alias, Method)` 和 `ctx.call` 转发接口方法，
并保留调用者的 inject 与拦截检查。它们是 Java 对动态属性与 mixin 的显式适配。

`new TimerService(ctx)` 提供 `timer` 服务，支持 timeout、interval、debounce 和 trailing-edge throttle。
经 `ctx.get("timer")` 获取的视图将每个定时器归属调用者 fiber；卸载时取消待执行回调。

## 验证与语义边界

当前有 77 个核心测试和 16 个业务示例测试，共 93 个测试用例。测试包含原有行为与新增的异步生命周期、回退异常、根关闭、消费者策略、域迁移、配置树/回写、
实际文件监听、JDK 动态编译、共享辅助类变更和多插件 HMR 失败恢复。
源码及文件监听集成测试使用临时目录和真实 JDK 编译器。

论文的正确性结论具有前提：插件作者提供的逆操作必须实际撤销效应，服务操作须遵守对应的可组合性约束。
框架不会自动撤销任意文件、网络请求或数据库写入，也不能证明用户提供的 inverse 正确。
本项目提供这些机制及其行为验证，不将单元测试等同于对整个 Java 实现的形式化证明。
