# AGENTS.md — spring-shop 项目协作规范

本文件面向在本仓库中工作的开发者与 AI 编码助手，用于统一协作方式与代码规范，保证代码风格一致、可维护、可学习。

## 项目概述

- **项目名称**：spring-shop
- **项目定位**：Java 电商网站后端，**以学习为主要目的**，单体应用架构（后续可扩展）
- **基础框架**：Spring Boot 3.5.16 + Maven 多模块 + Java 21
- **持久层**：MyBatis-Plus 3.5.17 + MySQL 8
- **当前状态**：已完成基础框架搭建（统一响应、全局异常处理、健康检查接口），业务模块待开发

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 语言 |
| Spring Boot | 3.5.16 | 框架 |
| Maven | - | 构建工具 |
| MyBatis-Plus | 3.5.17 | ORM / 持久层 |
| MySQL | 8.x | 数据库 |
| spring-boot-starter-validation | - | 参数校验 |

## 项目结构

```
spring_shop/
├── pom.xml                     # 父 POM：统一依赖管理
├── spring-shop-common/         # 公共模块：不依赖业务
│   └── src/main/java/com/springshop/common/
│       ├── config/             # 全局配置（如 MybatisPlusConfig）
│       ├── exception/          # 业务异常 + 全局异常处理
│       └── result/             # 统一响应 Result / ResultCode
└── spring-shop-web/            # 启动模块：依赖 common
    └── src/main/java/com/springshop/web/
        ├── controller/         # 控制器层
        ├── SpringShopApplication.java  # 启动类
        └── resources/application.yml   # 配置
```

### 模块职责

- **spring-shop-common**：跨模块共享的公共代码，**禁止出现业务逻辑**，只放通用能力（统一响应、异常、配置、工具类、通用枚举等）。
- **spring-shop-web**：应用启动模块，含启动类、控制器与配置文件，依赖 common 模块。

### 新增业务模块约定

新增业务模块时（如 `spring-shop-user`、`spring-shop-order`），应遵循：

1. 在父 POM 的 `<modules>` 中注册，并在 `<dependencyManagement>` 中声明版本。
2. 模块内部按 `controller / service / mapper / entity / dto / vo` 分层组织包。
3. 业务模块依赖 `spring-shop-common`；web 启动模块统一依赖所有业务模块。

## 分层架构规范

所有业务代码遵循经典 MVC + Service 分层，包名统一为 `com.springshop.<module>.<layer>`：

| 层 | 包名 | 职责 | 说明 |
|----|------|------|------|
| 控制层 | `controller` | 接收请求、参数校验、调用 service | 只做参数接收与结果返回，不写业务逻辑 |
| 服务层 | `service` | 业务逻辑 | 接口 + impl 实现，事务在此层管理 |
| 持久层 | `mapper` | 数据库访问 | 继承 `BaseMapper<T>`，复杂 SQL 放 XML |
| 实体层 | `entity` | 数据库表映射 | 与表字段一一对应 |
| DTO | `dto` | 入参对象 | 请求体 / 查询参数 |
| VO | `vo` | 出参对象 | 响应给前端的数据 |

**分层红线**：
- Controller 不直接操作 Mapper。
- Entity 不直接返回给前端，对外统一使用 VO。
- 跨层传递使用 DTO/VO，不直接暴露数据库实体。

## 编码规范

### 类结构

类内成员按 **先声明字段，再写构造函数，最后是方法** 的顺序组织（项目约定，参考 `Result.java`）。

### 命名规范

- 包名：全小写，`com.springshop.<module>.<layer>`
- 类名：大驼峰（`OrderService`、`UserController`）
- 方法 / 变量：小驼峰
- 常量：全大写 + 下划线（如 `SYSTEM_ERROR`）
- 数据库表：`snake_case`，实体字段使用驼峰，由 MyBatis-Plus 自动映射（`map-underscore-to-camel-case: true`）

### 注释规范

- 类、公共方法使用 **中文 Javadoc**（`/** ... */`）说明用途。
- 复杂业务逻辑在关键步骤处添加行内注释（中文）。
- 不要为显而易见的自解释代码添加冗余注释。

### 统一响应

所有 Controller 接口必须返回 `Result<T>` 包装：

```java
@GetMapping("/health")
public Result<String> health() {
    return Result.success("ok");
}
```

- 成功：`Result.success(data)` / `Result.success()`
- 失败：抛出 `BusinessException`，由 `GlobalExceptionHandler` 统一处理，**禁止在 Controller 里直接 return Result.fail(...) 处理业务失败**。

### 异常处理

- 业务校验失败抛出 `BusinessException`，并指定合理的错误码。
- 需要新增响应码时，在 `ResultCode` 枚举中扩展，**不要使用魔法数字**。
- 全局兜底由 `GlobalExceptionHandler` 负责（参数校验、类型转换、未知异常）。

### 参数校验

- 入参 DTO 使用 `spring-boot-starter-validation` 的注解（`@NotBlank`、`@NotNull`、`@Size` 等），并在 Controller 参数上加 `@Valid` / `@Validated`。

### MyBatis-Plus 约定

- Mapper 接口继承 `BaseMapper<T>`。
- 简单 CRUD 用 MP 内置方法，复杂 SQL 写在 `src/main/resources/mapper/**/*.xml`（对应 `mapper-locations: classpath*:/mapper/**/*.xml`）。
- 主键自增（`id-type: auto`），实体主键字段标 `@TableId(type = IdType.AUTO)`。

## 数据库配置约定

- 默认连接 `localhost:3306/spring_shop`，用户名 `root`。
- 密码通过环境变量 `MYSQL_PASSWORD` 覆盖，**禁止把真实密码硬编码提交**。

## 构建与运行

```bash
# 全量构建
mvn clean package

# 仅编译检查
mvn compile

# 运行 web 模块
mvn -pl spring-shop-web -am spring-boot:run

# 启动后健康检查
curl http://localhost:8080/api/health
```

## 工作流规范

### 开发任务流程

1. **先读后改**：修改任何文件前，先阅读目标文件及相关上下文。
2. **小步提交**：一个功能一个改动，保持 diff 聚焦。
3. **保持风格一致**：新代码遵循本文件与现有代码风格。
4. **学习导向**：作为学习项目，代码中可保留适度注释解释「为什么这样做」，方便复习。

### 代码提交

- 提交信息使用中文，遵循格式：`<类型>: <简述>`
  - 类型：`feat`（新功能）、`fix`（修复）、`refactor`（重构）、`docs`（文档）、`chore`（杂项）
  - 示例：`feat: 新增用户注册接口`
  - 简述不超过 100 字符
- **提交信息已启用自动校验**：仓库根目录 `.githooks/commit-msg` 会拦截不合规的提交。首次 clone 后需执行一次 `git config core.hooksPath .githooks` 才能生效。
- 不提交 `target/`、IDE 配置等（已在 `.gitignore` 中排除）。

### 禁止事项

- 禁止在 common 模块写业务逻辑。
- 禁止直接修改 `target/` 目录下的产物。
- 禁止提交真实数据库密码、密钥等敏感信息。
- 禁止在 Controller 中写业务逻辑或直接访问 Mapper。
