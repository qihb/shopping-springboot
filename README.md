# spring-shop

一个用于 **Java 学习** 的电商网站后端项目，基于 Spring Boot 单体应用架构。项目从零搭建基础框架，逐步演进为完整的电商后端（用户、商品、订单、购物车等模块）。

## 项目目的

- **学习为主**：以电商业务为载体，系统学习 Java 后端开发的核心技术栈与工程实践。
- **循序渐进**：先搭建稳定、规范的基础框架，再按业务模块逐步迭代，每个模块都遵循统一的分层与编码规范。
- **可扩展**：采用 Maven 多模块架构，后续新增业务模块不影响既有代码。

## 技术架构

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | |
| 框架 | Spring Boot 3.5.16 | 自动化配置、内嵌容器 |
| 构建 | Maven | 多模块管理 |
| 持久层 | MyBatis-Plus 3.5.17 | 通用 CRUD、分页插件 |
| 数据库 | MySQL 8 | |
| 参数校验 | spring-boot-starter-validation | DTO 注解校验 |

### 模块划分

```
spring-shop
├── spring-shop-common   公共模块：统一响应(Result)、响应码枚举(ResultCode)、
│                        业务异常(BusinessException)、全局异常处理(GlobalExceptionHandler)、
│                        MyBatis-Plus 配置(分页插件)——禁止包含业务逻辑
└── spring-shop-web      启动模块：SpringBoot 启动类、控制器、配置文件，依赖 common
```

### 基础能力（已实现）

- **统一响应体**：所有接口返回 `Result<T>`（code / message / data），成功、失败响应规范统一。
- **全局异常处理**：业务异常、参数校验异常、类型转换异常、未知异常统一兜底，前端无需关心错误细节。
- **响应码枚举**：`ResultCode` 统一管理状态码，杜绝魔法数字。
- **MyBatis-Plus 集成**：分页插件、下划线转驼峰映射、主键自增已就绪。
- **健康检查接口**：`GET /api/health` 用于验证服务是否正常启动。

## 业务规划

面向电商场景，规划中的业务模块（逐步实现）：

- 用户模块：注册、登录、个人中心
- 商品模块：分类、商品列表、商品详情
- 购物车模块：加购、修改、结算
- 订单模块：下单、支付、订单状态流转

> 当前阶段已完成基础框架搭建，业务模块尚未开发。

## 项目启动

### 环境要求

- JDK 21
- Maven 3.6+
- MySQL 8（本地运行）

### 1. 准备数据库

```sql
-- 创建数据库（字符集 utf8mb4 可支持完整中文与 emoji）
CREATE DATABASE spring_shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置数据库连接

编辑 [application.yml](spring-shop-web/src/main/resources/application.yml)，默认连接：

```
url: jdbc:mysql://localhost:3306/spring_shop
username: root
password: 通过环境变量 MYSQL_PASSWORD 指定，未设置时默认 root
```

```bash
# 若本机 MySQL 密码不是 root，启动前设置环境变量
export MYSQL_PASSWORD=你的密码
```

### 3. 编译 & 启动

```bash
# 编译整个项目
mvn clean compile

# 启动 web 模块
mvn -pl spring-shop-web -am spring-boot:run
```

### 4. 验证

```bash
curl http://localhost:8080/api/health
# 期望返回：
# {"code":200,"message":"操作成功","data":"spring-shop service is running"}
```

## 测试账号

当前项目尚未开发用户模块，**暂无业务测试账号**。业务模块开发完成后，测试账号将补充在此处，例如：

| 账号 | 密码 | 角色 | 说明 |
|------|------|------|------|
| admin | （待补充） | 管理员 | 后台管理 |

> 提示：当前仅使用本机开发数据库，连接账号（`root`）仅用于本地调试，请勿在生产环境复用。

## 相关文档

- [AGENTS.md](AGENTS.md) — 项目协作规范（分层架构、编码规范、提交约定）
