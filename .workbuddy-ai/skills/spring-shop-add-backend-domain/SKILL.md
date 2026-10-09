---
name: spring-shop-add-backend-domain
description: 在 spring-shop 仓库中新增一个后台 CRUD 域（数据表 → 实体 → Mapper → DTO/VO → Service → Controller → 权限注册 → 测试），或把某个字段的「真相表」换到新表（读/写路径搬家）时使用。覆盖 Flyway 迁移脚本与 migration-test H2 副本的写法与三个建表坑、分层包结构、@PreAuthorize 权限码必须同步注册进 AdminDataInitializer 否则 CI 红、测试四件套、纯 Mockito 单测的两个编译/运行坑（lambda 缓存未预热、insert(any()) 歧义）、以及搬家时必须同步修的测试（手工造数据的集成测试要补新表、Mockito 默认返回值会静默翻车）和必须逐个核对的缓存失效责任边界（新字段进了被缓存的 VO 时，每条改变它的写路径都要失效，含本轮新加的后台接口）。也用于排查「接口 403 但代码和 token 都正常」「H2 建表失败」「can not find lambda cache for this entity」「对 insert 的引用不明确」「换表后一堆用例报库存不足」「后台改了库存前台不刷新」这类具体问题。
agent_created: true
---

# spring-shop 新增后台 CRUD 域

## 这个技能解决什么

给 spring-shop 加一个**新的后台可管理领域**（如品牌、商品属性、优惠券）时，
需要一次性改动 6~8 层代码 + 1 个迁移脚本 + 1 份 H2 测试副本 + 1 处权限注册 + 2 类测试。
步骤多、跨模块，且其中三处会**静默或难以定位地失败**：

1. 迁移脚本在 H2 上建表失败（MySQL 语法差异 / H2 保留字）；
2. 接口写好了但**永久 403**（权限码没注册进初始数据）；
3. 单测编译不过或运行时报 lambda 缓存缺失。

`AGENTS.md` 是**规范来源**（分层、错误码段位、统一响应、迁移约定）；
本技能是**可执行的操作清单 + 坑位地图**。

## 什么时候用

- 新增一个后台管理领域（CRUD 接口 + 表）。
- 给已有领域加表 / 加列。
- 排查：新接口 403、H2 建表失败、`can not find lambda cache for this entity`、
  `对 insert 的引用不明确`、Flyway 校验失败。

## 执行清单（按顺序，缺一步都会在 CI 或运行期暴露）

### 1. Flyway 迁移脚本（两份，必须同步）

- 主脚本：`spring-shop-web/src/main/resources/db/migration/V{n}__描述.sql`
- H2 副本：`spring-shop-web/src/test/resources/db/migration-test/V{n}__描述.sql`
- **新脚本不带 `IF NOT EXISTS`**（只有 V1~V3 为兼容历史手建库保留）。
- **已提交的脚本永不修改**，只追加新版本。
- ⚠️ 改动表结构时两份都要改，否则测试库与生产库漂移。

### 2. 三个建表坑（踩过，直接用结论）

| 坑 | 现象 | 对策 |
|---|---|---|
| **`VALUE` 是 H2 保留字** | 列名用 `value` → H2 建表失败 | 换名（本项目用 `attr_value`），Java 字段同步改 `attrValue` |
| **`ALTER` 的 MySQL 专有语法** | `AFTER xxx` / `ADD KEY` 在 H2 报错 | 副本里拆成 `ADD COLUMN ...` + `CREATE INDEX ...`（对照 `migration-test/V6__user_miniapp.sql`） |
| **索引名全局唯一（CI 红线）** | H2 索引名是**库级**唯一，MySQL 只要求表级 → 两份脚本里同名索引撞车 | 索引名一律**前缀化**（`idx_pa_*` / `uk_ssv_*`），主脚本与副本保持一致 |

### 3. 分层包结构

业务模块内统一 `com.springshop.<module>.<layer>`：

```
entity/Xxx.java          @TableName + @TableId(AUTO) + @TableLogic isDeleted + @Version version
mapper/XxxMapper.java    extends BaseMapper<Xxx>  +  @Mapper
dto/XxxSaveRequest.java  @NotBlank / @NotNull + @Schema；分页查询继承 PageQuery（参数是 current/size）
vo/XxxVO.java            出参，绝不直接返回 entity
service/XxxService.java  + service/impl/XxxServiceImpl.java
controller/admin/AdminXxxController.java   @RequestMapping("/api/admin/xxxs")，返回 Result<T>
```

**表类型决定带不带逻辑删除/乐观锁**（本项目既有惯例）：

| 表类型 | is_deleted | version | update_time | 例子 |
|---|---|---|---|---|
| 普通业务表 | ✅ | ✅ | ✅ | `brand`、`inventory`、`product_attribute` |
| **append-only 流水** | ❌ | ❌ | ❌ | `inventory_log`、`operation_log` |
| **纯关联表**（物理删除） | ❌ | ❌ | ❌ | `sku_spec_value`、`admin_user_role`、`role_menu` |

### 4. 错误码：用本模块段位

`ResultCode` 按段位分配（0~99 公共 / 1000 用户 / 2000 商品 / 3000 购物车 / 4000 订单 /
5000 管理后台 / 6000 支付 / 7000 运营）。**先读文件确认空号再占**，不要用魔法数字。
（商品域 2030~2032 库存、2040~2042 品牌、2050~2056 属性已被占用。）

### 5. ⚠️ 权限注册（漏了就是永久 403）

每个 `@PreAuthorize("hasAuthority('xxx')")` 的权限码，**必须**在
`spring-shop-admin/.../config/AdminDataInitializer#initMenus` 里用 `ensureMenu(...)` 注册，
否则调用方永久 403（接口、代码、token 都正常，就是没权限，极难定位）。

- 幂等键是 `permission_code`，重复启动不会重复插入。
- 注册进去的菜单会被统一授予 `ADMIN` 角色。
- **不用手写对账测试**：`AdminPermissionCoverageIntegrationTest` 会反射扫出容器里所有
  `@PreAuthorize` 的 authority，与 `menu` 表 + ADMIN 授权对账，漏一个直接红。
- 命名惯例：`<域>:<资源>:<动作>`，如 `product:brand:list` / `product:attribute:update`。
- 只有 `isAuthenticated()`（Excel 任务中心）在白名单里，不参与对账。

### 6. 测试（两类都要写）

**四件套**（少一个就红）：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`
+ `@Transactional`，**外加** `@MockBean StringRedisTemplate`（测试环境无 Redis）。

- **纯 Mockito 单测**：钉守卫逻辑（重复名校验、删除守卫、异常码、写入的参数）。
- **真 H2 集成测试**：钉 SQL 正确性（列名映射、条件更新、排序、`insertBatch` 多行）。
  纯单测把 mapper mock 掉后，**SQL 根本没被执行**，列名写错也发现不了。

### 7. 验证

```bash
JAVA_HOME=/Users/qihaibing/.sdkman/candidates/java/21.0.9-amzn \
  /opt/homebrew/bin/mvn -f /Users/qihaibing/Documents/Trae/spring_shop/pom.xml \
  -B --no-transfer-progress test \
  -Dtest=新单测,新集成测试,AdminPermissionCoverageIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

属性名是 **`surefire.failIfNoSpecifiedTests`**（不是 `failIfNoSpecifiedTests`）。
最后跑一次**全量** `mvn test` 确认没有回归。

⚠️ **改完测试源码后必须 `clean test-compile`**：`mvn test-compile` 走增量编译，
未变更的测试类会被跳过，**真的编译错误也能报 BUILD SUCCESS**。
本轮就吃过一次亏（跑出假的 SUCCESS，`clean` 后才暴露真错）。

⚠️ **单模块跑必须带 `-am`**：`mvn -pl <module> test`（不带 `-am`）会从
`~/.m2/repository/com/springshop/**` 解析**兄弟模块的旧快照 jar**。症状是一堆
看起来毫不相干的 `NoSuchMethodError` / `NoSuchFieldError`
（实测：`ExcelReadOptions.expectedHeaders`、`ResultCode.PRODUCT_ATTRIBUTE_VALUE_IN_USE`），
极易误判成「自己的改动搞坏了 common」。⇒ 加 `-am`，或直接跑全量 reactor。

## 两个单测坑（会直接卡住构建）

### 坑 1：`verify(mapper).insert(any())` 编译不过

MyBatis-Plus 3.5.17 的 `BaseMapper` 同时有 `insert(T)` 和 `insert(Collection<T>)`：

```
对 insert 的引用不明确 —— BaseMapper 中的方法 insert(T) 和 insert(java.util.Collection<T>) 都匹配
```

⇒ 必须显式给类型：`verify(mapper, never()).insert(any(XxxEntity.class))`。

### 坑 2：`can not find lambda cache for this entity`

纯 Mockito 单测没有 Spring 容器，`TableInfo` 不随 Mapper 扫描注册；
服务层一旦构造 `LambdaQueryWrapper` 就可能抛
`MybatisPlusException: can not find lambda cache for this entity [Xxx]`。

**⚠️ 最阴的地方**：同一个测试类里走 `eq()` 的用例可能是绿的，只有走 **`in(...)`** 的炸
（`AbstractWrapper.in` → `columnToMapping` → `getColumnCache` → `tryInitCache` 断言失败）。
所以**不要赌 MP 会自己初始化**，只要服务层会构造 lambda wrapper 就补预热：

```java
@BeforeAll
static void warmupMybatisPlusLambdaCache() {
    Class<?>[] entities = new Class<?>[] { Xxx.class, Yyy.class };
    for (Class<?> entityClass : entities) {
        try {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
        } catch (Exception ignore) {
            // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
        }
    }
}
```

（对齐 `ProductQueryServiceImplTest` 的既有写法。）

## 守卫设计的两条经验

- **删除守卫要分两层**：先卡「有子记录」（避免悬空引用），再卡「被关联表引用」
  （否则关联方的展示会缺列）。两层的错误码要分开，否则运营不知道该去删哪个。
- **归属校验落在 SQL 条件里**，不要「先按 id 查出来再比较父 id」——
  写成 `eq(id).eq(parentId)` 一起查，语义更准，也少一次分支。

## 把某字段的「真相表」换掉时（如 `product_sku.stock` → `inventory`）

新增表往往伴生一次**读/写路径搬家**：某个字段不再由老列说了算。
这一步的真正工作量**不在主代码，在测试** —— 因为所有「手工造数据」的测试都成了假数据。

### 主代码侧

1. **老列降级为迁移期镜像**：新路径只写新表，老列不再维护（但也不删，留到下一个
   迁移版本再 drop）。**同时必须给老列写注释说明它已过期**，否则下一个人会继续读它。
2. **读路径要 fail-closed**：新表查不到行时按 0（不可售）处理，而不是抛异常 ——
   一条脏数据不该把商品详情页打成 500。
3. **在注释里点明遗留缺陷**（如「更新商品走整批重建 → 旧库存行变孤儿」），
   不要偷偷留坑。
4. **⚠️ 逐个核对缓存失效（最容易漏，且是「自己新引入」的 bug）**：
   新字段如果进了**被缓存的 VO**（本项目的 `product:detail:{productId}` 会整对象序列化），
   那么**每条能改变它的写路径都必须失效那个 key** —— 包括你**本轮新加的后台接口**
   （它们没有调用方兜底，只能自己在 service 里失效）。
   反过来也要想清楚**哪些路径不需要**：本项目 `inventory.outbound` 就不需要，
   因为可售量 = `stock − locked`，出库时两者同时减 q、差值恒等。
   ⇒ **把「不需要失效」也写成测试钉住**，否则后人会为「对称」加一次无谓的 Redis 删除。
   （实例：`InventoryServiceImplTest#outbound_shouldNotEvictDetailCache_becauseAvailableIsInvariant`）
5. **失效的 key 要用对的 id**：拿 `skuId` 去拼 `productDetail(...)` 会**永远删不掉**缓存，
   而且不报错。集成测试里顺带断言一次 key 的具体值最省事。

### 测试侧（**最容易漏，且会跨模块**）

搬家后，**凡是自己 INSERT 老表数据的测试，都必须同时 INSERT 新表**。
否则 fail-closed 的读路径会把它们全部判成「库存不足」。

- **真 H2 集成测试**：测试里的 `createSku(...)` 之类工厂方法绕过业务创建路径，
  不会触发新表的初始化。必须手工补一行。
- **纯 Mockito 单测**：给被测服务补上新依赖的 `@Mock` 并 stub。
  ⚠️ Mockito 的默认返回值有**两种**后果，别混：
  - 返回 `Map` 的方法 → 默认**空 Map**（不 NPE，静默按 0 处理，**看不出问题**）；
  - 返回 `int` 的方法 → 默认 **0** → 把「本该成功」的用例**静默翻成失败**
    （表现为 `expected BusinessException but was NullPointerException`，
    因为依赖是 null 时连 stub 都没生效）。
- **找全测试的姿势**：别只看你改的那个模块。按**老列 setter** 全仓库 grep
  （如 `setStock(`），调用点通常散在 `spring-shop-web` 的集成测试里。
- **断言要跟着搬家**：老列不再被维护 ⇒ 对它的断言永远「通过」且毫无意义。
  把断言改到新表（在库量 / 锁定量），并**顺手把语义写进用例名与注释**
  （如「下单只锁定、不改在库量」），否则名字和断言会对不上。
- **顺手补回归断言**：搬家常顺手修掉一类并发缺陷（如「重复支付不能重复出库」），
  在既有用例里加一行断言即可钉住，比新写用例划算。

### 新语义一定要写进测试类 Javadoc

老用例的注释会变成误导（「初始库存 10，买 2 → 8」在新模型下是错的）。
在类级 Javadoc 里用 3 行说清新模型的三态流转，比在每个用例里重复解释便宜。

## 改一条跨表业务规则的语义时（如导入从「按名称整组拒绝」→「按 (名称,规格) 逐行判定」）

改规则的**判定单元**（「名称」→「名称 + 规格」）比改规则本身更容易出事：判定单元一旦跨表，
查重 SQL 的 JOIN 方式、消费方对空值的处理、以及「谁有权创建主记录」都要一起改。

1. **`LEFT JOIN` / `INNER JOIN` 是语义决策，不是写法偏好**。本例必须 LEFT JOIN：
   库里存在「子表行全被逻辑删除、主记录还在」的数据（商品还在、SKU 全删了）。
   用 INNER JOIN 这类记录在查重结果里**完全消失** ⇒ 调用方以为该名称没人用 ⇒
   再建一个同名主记录，正是规则要消灭的现象。
   ⚠️ 逻辑删除条件（`s.is_deleted = 0`）必须写在 **`ON`** 里；写进 `WHERE` 会让 LEFT JOIN 退化成 INNER JOIN。
2. **代价要显式约定**：LEFT JOIN 会让子字段出现 `null`，消费方**必须跳过 `null` 行** ——
   那不是「占用了一个空值」，而是「压根没有子记录」。写进 Mapper Javadoc，并在**每个消费方**各钉一条测试。
3. **查重 SQL 返回什么，取决于调用方要干什么**：这里同时需要「有没有被占用」（拒重复）
   和「主记录 id 是谁」（复用主记录追加子行）。一次查询带出 `productId` 就够，别写两条 SQL。
4. **「逐行判定」必须真的逐行**：主记录的创建与子行的校验要**解耦** ——
   一组里有的行合法、有的行非法时，合法的照常落库。别把行级失败升级成整组失败。
5. **「复用主记录」分支要明确忽略主记录级字段**：只追加子行就不该写主记录的列，也不该校验它们。
   **这个取舍要写进 Javadoc 并主动告诉用户**，因为「顺带更新」也是合理预期。
6. **落库按「新建 / 复用」分支**：批量插主记录前先 filter 掉复用组；
   再统一给子行补主键 —— 新建的用 `insertBatch` 回填的 id，复用的用既有 id。
   用**工厂方法**（`create(...)` / `reuse(...)`）表达二选一，别用两个可空字段的裸构造器。
7. **桩的连带影响**：复用分支本来就不该建主记录 ⇒ 那个 `insertBatch` 的桩要 `lenient()`；
   而「所有行都被拒」的用例**干脆别调 `stubInsertAssignsId()`**，否则整组桩都是无用桩（严格模式直接红）。

### 此前无覆盖的接口，要专门建一个集成测试类

「改了没人测的东西」是最高频的漏网方式。本例三个 bug（改商品必 500 / 库存被静默清零 /
子行 id 全变）能漏到线上，唯一原因是**集成测试里没有任何用例打过
`PUT /api/admin/products/{id}`**，而写服务单测是纯 Mockito（mapper 被 mock、不碰库）
⇒ 结构性不可见。

**动手前先 grep 测试里有没有这个 URL**；没有就新建一个类
（如 `ProductUpdateIntegrationTest`），把这三种最容易踩的组合各写一条：

- **同一个唯一键改两次**（沿用同一个 `sku_code` / 同一 `(名称,规格)`）；
- **省略可选字段**（不传 `stock` ⇒ 必须保持原值，不能置 0）；
- **子行增删**（请求里去掉一行 ⇒ 该行被逻辑删除；新增一行 ⇒ 落库并挂对主记录 id）。

另：断言要落在**新的真相表**上（查 `inventory` 表，而不是 `product_sku.stock` 镜像）。

### 删列 / 改表结构：**已发布的 Flyway 脚本一个字节都不能改**

本项目的 Flyway 用默认 `validate-on-migrate=true`，**每个脚本的校验和都写进了
`flyway_schema_history`**。所以「顺手把 V2 里那行列定义也删掉，让新库直接不带这列」
这种看起来很干净的改法，实际效果是：**所有已经跑到该版本的库（含本机 dev）启动即校验失败**。

**正确做法永远是纯增量**：

```
V2 建列 → V9 回填 → V10 删列      # 新库与老库最终结构一致，老库只多跑一条 ALTER
```

**删列前必须先跑一致性检查**（结果必须 0 行，否则还有写入路径在改那一列，DROP 会静默丢数据）：

```sql
SELECT s.id, s.sku_code, s.stock AS old_val, i.stock AS new_val
FROM <table> s LEFT JOIN <new_truth_table> i ON i.<fk> = s.id
WHERE s.is_deleted = 0 AND (i.id IS NULL OR s.<col> <> i.<col>);
```

**这个检查刻意不写进脚本**：`db/migration` 与 `db/migration-test` 两个副本要分别跑
MySQL 8 与 H2，「不一致即中止」需要 `SIGNAL`/存储过程，两方言写法不同，
硬塞进去会牺牲可移植性 ⇒ 写进脚本头注释，由发布流程执行。

改完记得**两个副本都要加**（`db/migration/V{n}__x.sql` + `db/migration-test/V{n}__x.sql`），
否则测试库与生产库结构分叉。

### 删掉一个列时，值怎么跟着走

列没了 ⇒ 实体上不能挂这个值，但下游往往**必须等到子记录拿到自增 id 之后**才能用
（如建 `inventory` 行要 `sku_id`，而 `insertBatch` 是自定义 `@Insert`、不回填主键，
得按业务键回查）。中间这段路需要一个载体：

```java
/** 一个待落库的子记录：实体 + 该行想要的目标值 */
private record SkuDraft(ProductSku sku, int stock) { }
```

比「在实体上留个 `@TableField(exist = false)` 临时字段」干净得多 —— 后者会让
「这个字段到底落不落库」变成需要读注解才知道的事。落库时用
`Map<String, Integer> valueByBizKey` 收集，回查 id 后再取用。

同时别忘**所有绕过 Mapper 的裸 SQL 写入点**：种子脚本、`sql/demo/*.py`、
集成测试里的 `jdbcTemplate.update("INSERT INTO ...")` —— 列一删它们全会炸，
而且**只有跑集成测试才会暴露**（单测走 mock 不碰库）。改完先 grep 一遍
`INSERT INTO <table>`。

### 已经改过了怎么办：诊断 + repair（2026-10-09 实际踩到）

上面那条规则在 V9 上被违反了：V9 应用到 dev 库后脚本又被重写（**mtime 比
`installed_on` 晚 11 秒**），把 `product_attribute_value.value` 改成 `attr_value`。
后果是 **dev 应用直接起不来**（`FlywayValidateException`），**而测试全绿** ——
因为测试跑的是 H2 全新库，永远走不到「脚本变了但库没变」这条路径。

**一眼定性**：脚本 mtime 晚于 `flyway_schema_history.installed_on` ⇒ 一定被改过。

**诊断：建全新对照库跑完整迁移，再逐表 diff**（不用启动应用、不用猜改了哪几行）

```bash
mysql -uroot -proot -e "CREATE DATABASE spring_shop_check CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
# 用下面的 Flyway Java API 把它迁到目标版本
norm() { mysqldump -uroot -proot --no-data --skip-comments --skip-dump-date --skip-set-charset \
  --default-character-set=utf8mb4 "$1" | grep -vE '^/\*!|^--|^$' | sed -E 's/ AUTO_INCREMENT=[0-9]+//'; }
diff -u <(norm spring_shop_check) <(norm spring_shop)
mysql -uroot -proot -e "DROP DATABASE spring_shop_check;"    # 用完即删
```

`flyway_schema_history` 自身 collation 的 diff 是噪声（建库默认排序规则不同），忽略。

**修复三步** —— ⚠️ **只 repair 不修结构是错的**：校验绕过了，列名还是旧的，运行时才炸。

1. `ALTER TABLE ...` 把问题库结构改成与新脚本一致；
2. `flyway repair` —— 重写校验和为当前文件值（**会改写迁移历史，先跟用户说明再动**）；
3. `flyway migrate` —— 继续应用后续版本。

**不启动 Spring 容器跑迁移 / repair**（启动应用会触发 `OrderTimeoutTask` 取消超时单、
`AdminDataInitializer` 写 menu 等副作用）：

```bash
mvn -q -pl spring-shop-web dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=runtime
java -cp "$(cat /tmp/cp.txt)" Migrate.java <db> <migrate|repair|validate|info> [target]
```

`Migrate.java` 用 `Flyway.configure().dataSource(url,"root","root")
.locations("filesystem:<abs>/db/migration")`，Java 21 单文件源码启动即可；
classpath 必须含 `flyway-core` + `flyway-mysql` + `mysql-connector-j`。

两个硬提醒：

- ⚠️ **查最新版本别用 `MAX(version)`**：`version` 是 VARCHAR，字典序下 `'9' > '10'`，
  明明到了 V10 也会返回 9。用 `ORDER BY installed_rank DESC LIMIT 1`。
- ⚠️ **动结构前先备份**：`mysqldump -uroot -proot --single-transaction --routines --triggers
  spring_shop > /tmp/backup.sql`（本项目 dev 库约 80KB / 28 表，很便宜）。

