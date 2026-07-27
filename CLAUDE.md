# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言规则

所有对话均使用中文。

## 构建与测试

```bash
# 构建项目
mvn compile

# 运行全部测试
mvn test

# 运行单个测试类
mvn test -Dtest=MainTest
```

技术栈：Java 26, Maven, Lombok (provided scope), JUnit Jupiter 6.0.3。

## 架构

GameDB 是一个自研的内存游戏数据库引擎，支持对实体字段变更的事务管理。

### 实体生命周期

- **Entity 接口** (`Entity.java`): 继承 `Codec`，用户定义的实体类（如 `User`）实现该接口并使用 `@T` 注解标注。
- **代理子类约定**: `Tables.create(User.class)` 通过反射加载 `_User`（命名规则: `_` + 类名），而非直接实例化 `User`。生成的 `_User` 子类会覆写 getter/setter，将字段访问拦截并路由到当前事务的日志中。
- **Table** (`Table<Entity>`): 基于 `ConcurrentHashMap<Comparable<?>, Record>` 的泛型内存存储。每条 `Record` 包装实体，包含版本号和状态（`DB`、`UPDATE`、`INSERT`、`DELETE`）。通过 `Tables.getTable(Class)` 从静态 Map 中获取单例表。

### 事务系统

- **`TransactionImpl`** 是核心。使用 `ThreadLocal<TransactionImpl>` 跟踪当前线程的事务。支持嵌套事务：子事务提交时将日志合并到父事务中；只有根事务提交时才真正执行日志。
- **`Log<V>`** 表示一个待提交的字段变更。`SimpleLog<V>` 存储值和一个 `Consumer<V>`（通常为 setter 方法引用）。`commit()` 时调用 consumer，`rollback()` 时丢弃日志。类型化变体: `IntLog`、`LongLog`、`StringLog`。
- **`Logic`** 是工作单元。`Logic.process()` 返回 `State.SUCCESS`、`EXCEPTION` 或 `RETRY`。通过 `TransactionImpl.submit()` 提交到固定大小的线程池（10 个线程），由 `LogicFuture` 包装执行。返回 `SUCCESS` 时提交子事务，其他状态则回滚。
- **锁机制**: `LockKey` 继承 `ReentrantLock` 并实现 `Comparable`（先按 name 再按 key 排序），根事务提交时按确定顺序加锁，避免死锁。

### Codec（序列化）

`Codec` 接口提供 `encode(Writer)` 和 `decode(Reader)` 两个默认方法。`Reader` 和 `Writer` 目前为空接口，尚未实现。

### 包结构

| 包名 | 用途 |
|---|---|
| `com.virtual` | 核心类：Entity, Table, Tables, Transaction, TransactionImpl, Logic, LockKey, Record |
| `com.virtual.codec` | 序列化接口（待实现）：Codec, Reader, Writer |
| `com.virtual.Log` | 字段变更日志：Log\<V\>, SimpleLog\<V\> 及类型化实现 |
| `com.virtual.api` | 用户侧注解（`@T`） |

## 编码规则

详见 `.claude/skills/coding-rules.md`：
1. **最小改动原则** — 以最精简的方式实现需求。
2. **不做顺手优化** — 需求范围外的代码，即使发现可优化之处也不能擅自改动，必须先向用户说明并取得同意。
3. **每次修改都要有测试** — 每次改动后必须编写可运行通过的测试用例。
