本页介绍 cordis-java 里四类"周边服务"：日志（Logger/LoggerService）、上下文属性（accessor）、上下文函数（mixin 与 call），以及定时器（TimerService）。它们都是挂在根上下文或普通上下文上的轻量工具，让插件不必自己引入额外依赖就能打日志、暴露属性和函数、做超时/节流调度。

## 日志系统：Logger 与 LoggerService

cordis 的日志分为两层：`LoggerService` 是全局的"分发中心"，`Logger` 是插件手里拿到的"命名日志器"。`LoggerService` 构造时默认注册了一个输出端（exporter），把日志按 `时间 [级别] 名字: 消息` 的格式打印到 `System.out`；你也可以通过 `addExporter`/`removeExporter` 挂接自己的输出端（写文件、上报等）。

`Logger` 提供 `info`/`warn`/`error`/`debug` 四个级别，使用 `{}` 占位符格式化：每遇到一个参数，就把第一个 `{}` 替换掉；如果替换不下去了就把参数追加到末尾。参数若是 `Throwable`，会渲染成带缩进的完整堆栈。所有日志都带 `HH:mm:ss.SSS` 时间戳。

最关键的一条设计是**输出端失败不阻断调用方**：分发日志时，每个 exporter 都被 try/catch 包住，抛出任何异常都会被忽略——日志只是旁路，绝不能把业务代码搞挂。

```java
// 应用侧：挂一个自定义输出端
ctx.exporter((ts, level, name, msg) -> fileWriter.println(ts + " " + msg));

// 插件侧：打日志
ctx.logger("order").info("订单 {} 已创建", orderId);
ctx.logger("timer").error(t);   // 直接打印异常堆栈
```

此外 `Context.exporter(...)` 是一个效应（effect）封装：当前 fiber 卸载时自动移除这个 exporter，避免泄漏。

Sources: [Logger.java](cordis-core/src/main/java/dev/cordis/core/runtime/Logger.java#L11-L64), [LoggerService.java](cordis-core/src/main/java/dev/cordis/core/runtime/LoggerService.java#L14-L48), [Context.java](cordis-core/src/main/java/dev/cordis/core/runtime/Context.java#L390-L399)

## accessor：上下文属性（计算属性）

`accessor` 允许你向上下文注册一个"属性"——它不是一条服务绑定（没有 fiber、没有隔离域），而是一对读/写函数：

- `read: Context -> Object`：**以调用者的上下文为参数**，所以同一个属性在不同上下文里可以读到不同的值（天然的"调用者视图"）。
- `write: (Context, Object) -> void`：可传 `null` 表示只读。

访问优先级很高：`get` / `set` 的第一步都会先查 accessor 表，命中就直接走它，根本不会进入服务查找流程（见 `getChecked` 与 `setValue` 的开头）。名字冲突也会被拦截：如果该名字已有 accessor 或已被某个服务 provide 过，会抛 `DUPLICATE_PROVIDE` 异常。

```java
ctx.accessor("db.host",
    caller -> caller.resolveIntercept("db").toString(),  // 读：随调用者配置变化
    null);                                               // 只读
```

Sources: [ReflectService.java](cordis-core/src/main/java/dev/cordis/core/coeffect/ReflectService.java#L24-L47), [Context.java](cordis-core/src/main/java/dev/cordis/core/runtime/Context.java#L204-L207), [Context.java](cordis-core/src/main/java/dev/cordis/core/runtime/Context.java#L260-L266)

## mixin 与 call：把接口方法变成"上下文函数"

`Context` 定义了函数式接口 `Invokable`（接收可变参数、返回 `Object`）。当一个 accessor 的**读结果**是 `Invokable` 时，它就成了"上下文函数"。

`mixin(service, alias, method)` 是最典型的构造方式：它把某个服务实例上的一个 `java.lang.reflect.Method` 暴露成名为 `alias` 的 accessor。读的时候返回一个闭包，闭包内部先以**调用者的上下文**去 `get(service)`（完整保留 inject 能力检查），再反射转发方法调用；`InvocationTargetException` 会通过 `Stages.propagate` 还原真实异常。也就是说：函数的**能力检查发生在调用时、以调用者为准**，而不是以 mixin 声明处为准。

`call(name, args...)` 则是使用端：`get(name)` 拿到的值必须是 `Invokable`，否则抛 `IllegalArgumentException`，然后直接调用并返回结果。

```java
// 提供方：把 db 接口的 query 方法暴露成 "db.query"
ctx.mixin("db", "db.query", Db.class.getMethod("query", String.class));

// 消费方（必须 inject 了相关能力才能拿到）：
ctx.call("db.query", "select * from orders");
```

Sources: [Context.java](cordis-core/src/main/java/dev/cordis/core/runtime/Context.java#L370-L388)

## 三种访问方式的对比

| 方式 | 注册物 | 是否有 fiber/隔离域 | 读时行为 | 典型用途 |
|---|---|---|---|---|
| `provide` | 服务值（`Impl` 绑定） | 是，受响应式依赖管理 | 沿 fiber 链能力检查后返回 | 核心服务（db、events） |
| `accessor` | 读/写函数对 | 否，挂在根上下文 | 以调用者上下文执行 read/write | 计算属性、派生值 |
| `mixin` + `call` | 经 accessor 的 `Invokable` | 否，但调用时做 `get` 检查 | 反射转发到服务方法，异常还原 | 把接口方法暴露成函数 |

Sources: [Context.java](cordis-core/src/main/java/dev/cordis/core/runtime/Context.java#L200-L238), [ReflectService.java](cordis-core/src/main/java/dev/cordis/core/coeffect/ReflectService.java#L33-L47)

## TimerService：定时器也是效应

`TimerService` 实现了 `ContextualService<TimerService>`——它是一个**上下文感知服务**：共享的调度线程池只有一份，但每次被别的上下文 `get` 时，会通过 `inContext(caller)` 返回一个绑定到调用者上下文的新视图。

构造时它做两件事：

1. 创建单线程的守护调度线程 `cordis-timer`，并把它注册为当前上下文的效应（fiber 卸载时 `shutdownNow`，不会留孤儿线程）；
2. `provide("timer", this)` 把自己注册为服务。

**定时回调是"调用者 fiber 的效应"**（类注释原话）。`timeout(callback, delay)` 和 `interval(callback, period)`（零周期非法、负延迟非法）都走 `schedule`：整个过程包在 `context.atomic` 里，先通过 `context.effect(...)` 登记效应拿到可取消的 handle，再向调度器提交任务。回调真正执行时还有一层防护：

- 只有当 fiber 的 `uid != null` 且状态不是 `UNLOADING` 时才执行——正在卸载的上下文不会再触发回调；
- 抛出的任何异常被捕获并用 `ctx.logger("timer").error(t)` 记录——**一个定时器报错不会杀掉调度线程**；
- 一次性任务执行完在 `finally` 里自动 `dispose()` 自己。

`dispose()` 即取消对应的 `ScheduledFuture`（`future.cancel(false)`）。

## debounce 与 throttle：受控回调

`TimerService` 还提供 `debounce` 与 `throttle`，两者共用内部类 `ControlledCallback`（同时实现 `Runnable` 和 `AutoCloseable`）。构造时它同样把"关闭状态 + 取消 pending"登记为效应，所以 **fiber 卸载会自动让回调失效**。

- **debounce（防抖）**：每次 `run()` 先取消未触发的 pending，再重新调度一个 `timeout`——只有最后一次调用会在延迟后真正执行。
- **throttle（节流，尾沿触发）**：interval 期间若已有 pending，直接忽略本次调用；即每个周期最多执行一次，使用**周期内的第一次请求**。

所有状态变更（`closed`、`pending` 的增删）都在 `context.atomic` 中进行，避免竞态。

```java
TimerService timer = new TimerService(ctx);
var throttled = timer.throttle(() -> refresh(), Duration.ofSeconds(1));
throttled.run();  // 高频触发也只按周期执行
throttled.close(); // 手动关闭；fiber 卸载时也会自动关闭
```

Sources: [TimerService.java](cordis-core/src/main/java/dev/cordis/core/runtime/TimerService.java#L14-L122)

## 概念关系图

```mermaid
graph TD
    CTX[Context] -->|logger(name)| LOG[Logger]
    LOG -->|write| LS[LoggerService]
    LS -->|分发, 单个失败被忽略| E1[Exporter 控制台]
    LS --> E2[自定义 Exporter]

    CTX -->|accessor| RS[ReflectService 属性表]
    CTX -->|mixin| RS
    RS -->|read 返回 Invokable| INV[Invokable 闭包]
    CTX -->|call| INV
    INV -->|调用时 get(service)| SVC[目标服务]

    CTX -->|new TimerService| TS[TimerService]
    TS -->|provide "timer"| CTX
    TS --> SCHED[单线程 cordis-timer]
    TS --> TO[timeout / interval]
    TS --> CB[debounce / throttle]
    TO & CB -->|登记为 effect| FX[Fiber 效应: 卸载自动取消]
```

理解本页的关键是两个统一的主题：**"调用者视角"**（accessor 的 read/write、mixin 转发时都以调用者上下文解析；TimerService 经 `inContext` 绑定调用者）和 **"一切都是效应"**（exporter、accessor、mixin、定时器、节流回调全部随 fiber 卸载自动清理）。

建议继续阅读：
- 服务查找与能力检查的完整规则见 [响应式依赖：provide/get 能力检查与 PENDING 自动激活](8-xiang-ying-shi-yi-lai-provide-get-neng-li-jian-cha-yu-pending-zi-dong-ji-huo)
- `ContextualService.inContext` 的更多细节见 [ContextualService 调用者视图：JVM 上拦截的具体类适配方案](11-contextualservice-diao-yong-zhe-shi-tu-jvm-shang-lan-jie-de-ju-ti-lei-gua-pei-fang-an)
- `intercept` 如何与这些服务配合（如让 db 只读）见 [intercept 原地更新与租户隔离实战（电商双店铺）](12-intercept-yuan-di-geng-xin-yu-zu-hu-ge-chi-shi-zhan-dian-shang-shuang-dian-pu)
- 效应与 LIFO 清理语义见 [效应与资源回退：Disposable、LIFO 逆操作与失败不阻断清理](6-xiao-ying-yu-zi-yuan-hui-tui-disposable-lifo-ni-cao-zuo-yu-shi-bai-bu-zu-duan-qing-li)