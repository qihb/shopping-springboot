# 商品 MVP 模块实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于现有 spring-shop 多模块单体框架，完成商品模块最小可用版本：商品分类管理、SPU/SKU/图片的后台管理，前台分类树、商品列表、商品详情查询，配套单元测试与 H2 集成测试全绿。

**Architecture:** 新增 `spring-shop-product` Maven 模块，内部按「分类子域 + 商品子域」拆分。后台写操作由 `CategoryService` 与 `ProductManageService` 承担（商品保存/修改一次性落 SPU、SKU、图片三张表）；前台展示由 `CategoryQueryService` 与 `ProductQueryService` 做只读查询；Controller 分层为 admin（需 RBAC 鉴权 + 操作审计）与 app（登录用户即可）。持久层复用现有 Flyway V2 表结构与 MyBatis-Plus 约定。

**Tech Stack:** Java 21 + Spring Boot 3.5.16 + Maven 多模块 + MyBatis-Plus 3.5.17 + Spring Security + JWT + JUnit5 + Mockito + H2（集成测试内存库）+ springdoc-openapi 注解

---

## 一、文件结构总览

**新增模块与配置**
- Create: `spring-shop-product/pom.xml` 商品模块 POM（依赖 common + validation + swagger-annotations）
- Modify: `pom.xml` 注册 product 模块并在 `<dependencyManagement>` 声明版本
- Modify: `spring-shop-web/pom.xml` 增加 product 依赖
- Modify: `spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java` 新增 2000~2099 商品错误码段

**分类子域（category）**
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/entity/ProductCategory.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/mapper/ProductCategoryMapper.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/dto/CategorySaveRequest.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/vo/CategoryVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/vo/CategoryNodeVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/service/CategoryService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/service/impl/CategoryServiceImpl.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/app/AppCategoryController.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/category/service/impl/CategoryServiceImplTest.java`

**商品子域（product）**
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/Product.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/ProductSku.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/ProductImage.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductMapper.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductSkuMapper.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductImageMapper.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductSaveRequest.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductSkuItem.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductImageItem.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductPageQuery.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductListVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductDetailVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/ProductManageService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/ProductQueryService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductManageServiceImpl.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductQueryServiceImpl.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/app/AppProductController.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductManageServiceImplTest.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductQueryServiceImplTest.java`

**集成测试**
- Create: `spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java`
- Modify: `spring-shop-web/src/test/resources/application-test.yml`（如扫描需要无需变动则跳过，保持原样）

**文档**
- Create: `docs/product-module.md`

---

## 二、逐任务实施

### Task 1：注册 spring-shop-product 模块并打通编译

**Files:**
- Create: `spring-shop-product/pom.xml`
- Modify: `pom.xml:10-17` 与 `pom.xml:30-62`
- Modify: `spring-shop-web/pom.xml`

- [ ] **Step 1: 写最小 POM，先故意不加模块声明让编译失败**

Create `spring-shop-product/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.springshop</groupId>
        <artifactId>spring-shop</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>spring-shop-product</artifactId>
    <name>spring-shop-product</name>
    <description>商品模块：分类、SPU、SKU、图片</description>

    <dependencies>
        <dependency>
            <groupId>com.springshop</groupId>
            <artifactId>spring-shop-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-annotations-jakarta</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 执行 compile 验证模块未注册时会报错**

Run: `mvn -pl spring-shop-product -am compile`
Expected: 失败，提示 spring-shop-product 不在 reactor 或找不到 parent 模块子声明（具体是父 POM 没有 `<module>spring-shop-product</module>`，构建顺序会报错）

- [ ] **Step 3: 修改父 POM 与 web POM**

Modify `pom.xml` `<modules>` section:

```xml
<modules>
    <module>spring-shop-common</module>
    <module>spring-shop-user</module>
    <module>spring-shop-admin</module>
    <module>spring-shop-product</module>
    <module>spring-shop-web</module>
</modules>
```

Modify `pom.xml` `<dependencyManagement>` section, 在 spring-shop-admin 声明后追加：

```xml
<dependency>
    <groupId>com.springshop</groupId>
    <artifactId>spring-shop-product</artifactId>
    <version>${project.version}</version>
</dependency>
```

Modify `spring-shop-web/pom.xml`，在现有 admin 依赖后追加：

```xml
<dependency>
    <groupId>com.springshop</groupId>
    <artifactId>spring-shop-product</artifactId>
</dependency>
```

- [ ] **Step 4: 跑全量 compile 验证依赖链**

Run: `mvn clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交骨架**

```bash
git add pom.xml spring-shop-product/pom.xml spring-shop-web/pom.xml
git commit -m "chore: 新增 product 模块骨架并接入 web"
```

---

### Task 2：扩展 ResultCode 的商品错误码段

**Files:**
- Modify: `spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java`

- [ ] **Step 1: 写一个集成测试先依赖新错误码（故意未定义）使测试编译失败**

为避免测试污染延后，这一步直接采用"小步 commit + 代码走查 + 后续测试兜底"的方式：先不写新测试，在 Task 11 集成测试中引用新错误码。本任务仅通过现有 `mvn compile` 检查。

- [ ] **Step 2: 执行 compile（基线通过）**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 在 ResultCode 增加商品段（2000~2099）**

在 ResultCode 中用户段之后、管理后台段之前插入：

```java
// 业务码：商品模块（2000 段）
PRODUCT_CATEGORY_NOT_FOUND(2001, "商品分类不存在"),
PRODUCT_CATEGORY_HAS_CHILDREN(2002, "分类下存在子分类，不可删除"),
PRODUCT_CATEGORY_HAS_PRODUCTS(2003, "分类下存在商品，不可删除"),
PRODUCT_NOT_FOUND(2010, "商品不存在"),
PRODUCT_SKU_NOT_FOUND(2011, "SKU 不存在"),
PRODUCT_SKU_CODE_DUPLICATE(2012, "SKU 编码重复"),
PRODUCT_SKU_EMPTY(2013, "商品至少需要一个 SKU"),
PRODUCT_OFF_SHELF(2014, "商品已下架");
```

- [ ] **Step 4: 跑 compile 校验枚举未冲突**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交错误码扩展**

```bash
git add spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java
git commit -m "feat: 新增商品模块 2000 段错误码"
```

---

### Task 3：分类子域 Entity + Mapper

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/entity/ProductCategory.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/mapper/ProductCategoryMapper.java`

- [ ] **Step 1: 先写 CategoryServiceImplTest 的空测试引用类，造成编译失败（先不写测试逻辑，为保持 TDD 节奏先占位编译失败后再实现类）**

为保持 Task 聚焦，此处直接使用"小步实现 + 后续 Task 单测覆盖"的方式；真正测试在 Task 4 写。这一步编译校验通过即可。

- [ ] **Step 2: 跑 compile 基线（应通过）**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 写 Entity 与 Mapper**

`ProductCategory.java`

```java
package com.springshop.product.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 商品分类实体
 */
@TableName("product_category")
public class ProductCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    private Integer sort;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    @Version
    private Integer version;

    public ProductCategory() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
```

`ProductCategoryMapper.java`

```java
package com.springshop.product.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.category.entity.ProductCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper
 */
@Mapper
public interface ProductCategoryMapper extends BaseMapper<ProductCategory> {
}
```

- [ ] **Step 4: compile 验证类无语法错误**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交分类实体层**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/category/
git commit -m "feat: 商品分类 entity 与 mapper 落地"
```

---

### Task 4：分类 Service 单测 + 实现（CRUD + 分类树）

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/dto/CategorySaveRequest.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/vo/CategoryVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/vo/CategoryNodeVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/service/CategoryService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/category/service/impl/CategoryServiceImpl.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/Product.java`（仅为删除分类校验时引用 count，最小化：只提供 entity）
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductMapper.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/category/service/impl/CategoryServiceImplTest.java`

- [ ] **Step 1: 写失败的单测**

`CategoryServiceImplTest.java`

```java
package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 分类服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private ProductCategoryMapper categoryMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private CategorySaveRequest request;

    @BeforeEach
    void setUp() {
        request = new CategorySaveRequest();
        request.setParentId(0L);
        request.setName("数码");
        request.setSort(1);
        request.setStatus(1);
    }

    @Test
    void should_create_success() {
        when(categoryMapper.insert(any(ProductCategory.class))).thenReturn(1);

        assertDoesNotThrow(() -> categoryService.create(request));
        verify(categoryMapper, times(1)).insert(any(ProductCategory.class));
    }

    @Test
    void should_update_throw_when_category_not_found() {
        when(categoryMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.update(99L, request));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_delete_throw_when_has_children() {
        ProductCategory category = new ProductCategory();
        category.setId(1L);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(categoryMapper.selectCount(Wrappers.<ProductCategory>lambdaQuery()
                .eq(ProductCategory::getParentId, 1L))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.delete(1L));
        assertEquals(ResultCode.PRODUCT_CATEGORY_HAS_CHILDREN.getCode(), ex.getCode());
    }

    @Test
    void should_get_by_id_throw_when_missing() {
        when(categoryMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> categoryService.getById(1L));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_tree_assemble_success() {
        ProductCategory root = new ProductCategory();
        root.setId(1L);
        root.setParentId(0L);
        root.setName("数码");
        root.setSort(1);
        root.setStatus(1);

        ProductCategory child = new ProductCategory();
        child.setId(2L);
        child.setParentId(1L);
        child.setName("手机");
        child.setSort(0);
        child.setStatus(1);

        when(categoryMapper.selectList(any())).thenReturn(List.of(root, child));

        List<CategoryNodeVO> tree = categoryService.tree();
        assertEquals(1, tree.size());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("手机", tree.get(0).getChildren().get(0).getName());
    }
}
```

同时给出最小 DTO/VO 与 entity/mapper 以便测试引用（后面 Step 3 完整写）：
`CategorySaveRequest.java`、`CategoryVO.java`、`CategoryNodeVO.java`、`Product.java`、`ProductMapper.java`、`CategoryService.java` 与 `CategoryServiceImpl.java` 的空壳。在真正执行此任务时 Step 1 只写上面测试文件，其他文件缺失会触发编译失败，符合"先红"原则。

- [ ] **Step 2: 跑单测验证编译失败或断言失败**

Run: `mvn -pl spring-shop-product test -Dtest=CategoryServiceImplTest`
Expected: FAIL（缺少对应类 / 方法）

- [ ] **Step 3: 实现 DTO/VO + Service 接口 + impl + 最小 Product entity/mapper**

`CategorySaveRequest.java`

```java
package com.springshop.product.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分类保存请求
 */
@Schema(description = "商品分类保存入参")
public class CategorySaveRequest {

    @Schema(description = "父分类 id，顶级传 0", example = "0")
    @NotNull(message = "父分类 id 不能为空")
    private Long parentId;

    @Schema(description = "分类名称", example = "数码")
    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用", example = "1")
    private Integer status;

    public CategorySaveRequest() {
    }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
```

`CategoryVO.java`

```java
package com.springshop.product.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 分类 VO（后台列表扁平用）
 */
@Schema(description = "分类详情")
public class CategoryVO {

    @Schema(description = "分类 id")
    private Long id;

    @Schema(description = "父分类 id")
    private Long parentId;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态")
    private Integer status;

    public CategoryVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
```

`CategoryNodeVO.java`

```java
package com.springshop.product.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 分类树节点 VO
 */
@Schema(description = "分类树节点")
public class CategoryNodeVO {

    @Schema(description = "分类 id")
    private Long id;

    @Schema(description = "父分类 id")
    private Long parentId;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "子分类")
    private List<CategoryNodeVO> children;

    public CategoryNodeVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<CategoryNodeVO> getChildren() { return children; }
    public void setChildren(List<CategoryNodeVO> children) { this.children = children; }
}
```

`Product.java`（最小实现，满足分类删除时"是否存在商品"判断所需的 selectCount 条件构造）

```java
package com.springshop.product.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品（SPU）实体
 */
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long categoryId;

    private String name;

    private String subtitle;

    private String mainImage;

    private String detail;

    private Integer sales;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    @Version
    private Integer version;

    public Product() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
```

`ProductMapper.java`

```java
package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
```

`CategoryService.java`

```java
package com.springshop.product.category.service;

import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;

import java.util.List;

/**
 * 分类服务
 */
public interface CategoryService {

    void create(CategorySaveRequest request);

    void update(Long id, CategorySaveRequest request);

    void delete(Long id);

    CategoryVO getById(Long id);

    List<CategoryNodeVO> tree();
}
```

`CategoryServiceImpl.java`

```java
package com.springshop.product.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分类服务实现
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private final ProductCategoryMapper categoryMapper;

    private final ProductMapper productMapper;

    public CategoryServiceImpl(ProductCategoryMapper categoryMapper, ProductMapper productMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
    }

    @Override
    public void create(CategorySaveRequest request) {
        ProductCategory category = new ProductCategory();
        apply(category, request);
        if (category.getSort() == null) {
            category.setSort(0);
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        categoryMapper.insert(category);
    }

    @Override
    public void update(Long id, CategorySaveRequest request) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        if (request.getParentId() != null
                && (request.getParentId().equals(id) || isDescendant(id, request.getParentId()))) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        apply(category, request);
        categoryMapper.updateById(category);
    }

    @Override
    public void delete(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        Long childCount = categoryMapper.selectCount(
                Wrappers.<ProductCategory>lambdaQuery().eq(ProductCategory::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_HAS_CHILDREN);
        }
        Long productCount = productMapper.selectCount(
                Wrappers.<Product>lambdaQuery().eq(Product::getCategoryId, id));
        if (productCount != null && productCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_HAS_PRODUCTS);
        }
        categoryMapper.deleteById(id);
    }

    @Override
    public CategoryVO getById(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        return toVO(category);
    }

    @Override
    public List<CategoryNodeVO> tree() {
        List<CategoryNodeVO> nodes = categoryMapper.selectList(
                        Wrappers.<ProductCategory>lambdaQuery().orderByAsc(ProductCategory::getSort))
                .stream().map(this::toNode).collect(Collectors.toList());

        Map<Long, List<CategoryNodeVO>> childrenMap = nodes.stream()
                .filter(node -> node.getParentId() != null && node.getParentId() != 0)
                .collect(Collectors.groupingBy(CategoryNodeVO::getParentId));

        return nodes.stream()
                .filter(node -> node.getParentId() == null || node.getParentId() == 0)
                .peek(node -> attach(node, childrenMap))
                .sorted(Comparator.comparing(CategoryNodeVO::getSort, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());
    }

    private void apply(ProductCategory category, CategorySaveRequest request) {
        if (request.getParentId() != null) {
            category.setParentId(request.getParentId());
        }
        if (request.getName() != null) {
            category.setName(request.getName());
        }
        if (request.getSort() != null) {
            category.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            category.setStatus(request.getStatus());
        }
    }

    private boolean isDescendant(Long selfId, Long candidateParentId) {
        if (candidateParentId == null || candidateParentId == 0) {
            return false;
        }
        ProductCategory current = categoryMapper.selectById(candidateParentId);
        while (current != null && current.getParentId() != 0) {
            if (current.getParentId().equals(selfId)) {
                return true;
            }
            current = categoryMapper.selectById(current.getParentId());
        }
        return false;
    }

    private void attach(CategoryNodeVO parent, Map<Long, List<CategoryNodeVO>> childrenMap) {
        List<CategoryNodeVO> children = childrenMap.getOrDefault(parent.getId(), List.of());
        children.sort(Comparator.comparing(CategoryNodeVO::getSort, Comparator.nullsLast(Integer::compareTo)));
        parent.setChildren(children);
        children.forEach(c -> attach(c, childrenMap));
    }

    private CategoryNodeVO toNode(ProductCategory c) {
        CategoryNodeVO node = new CategoryNodeVO();
        node.setId(c.getId());
        node.setParentId(c.getParentId());
        node.setName(c.getName());
        node.setSort(c.getSort());
        node.setStatus(c.getStatus());
        return node;
    }

    private CategoryVO toVO(ProductCategory c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        return vo;
    }
}
```

- [ ] **Step 4: 跑单测验证通过**

Run: `mvn -pl spring-shop-product test -Dtest=CategoryServiceImplTest`
Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 5: 提交分类服务**

```bash
git add spring-shop-product/src/main/java/com/springshop/product spring-shop-product/src/test/java/com/springshop/product
git commit -m "feat: 分类子域 service 落地（CRUD + 树）并补单测"
```

---

### Task 5：后台分类接口 + 权限注解 + 操作审计

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java`
- Modify: `spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java`（追加"商品管理"菜单初始化）

- [ ] **Step 1: 写 ProductIntegrationTest 分类接口 401 用例（未登录访问应 401），测试先编译失败（缺少 Controller 路径）**

`spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java` 先写最小骨架：

```java
package com.springshop.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void admin_category_tree_should_require_login() throws Exception {
        mockMvc.perform(get("/api/admin/categories/tree")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 跑集成测试，预期 401 断言前先因为路径 404（Controller 还没写）而失败**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest`
Expected: FAIL（status 404 != 401 或编译缺类）

- [ ] **Step 3: 写 AdminCategoryController，对齐 MenuController 风格**

```java
package com.springshop.product.controller.admin;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.category.dto.CategorySaveRequest;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import com.springshop.product.category.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台商品分类接口
 */
@Tag(name = "后台商品分类管理")
@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "分类树")
    @PreAuthorize("hasAuthority('product:category:list')")
    @GetMapping("/tree")
    public Result<List<CategoryNodeVO>> tree() {
        return Result.success(categoryService.tree());
    }

    @Operation(summary = "分类详情")
    @PreAuthorize("hasAuthority('product:category:list')")
    @GetMapping("/{id}")
    public Result<CategoryVO> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    @Operation(summary = "新增分类")
    @OperationLog(module = "商品管理", operation = "新增分类")
    @PreAuthorize("hasAuthority('product:category:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody CategorySaveRequest request) {
        categoryService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改分类")
    @OperationLog(module = "商品管理", operation = "修改分类")
    @PreAuthorize("hasAuthority('product:category:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除分类")
    @OperationLog(module = "商品管理", operation = "删除分类")
    @PreAuthorize("hasAuthority('product:category:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
```

同时在 `AdminDataInitializer` 的菜单初始化里追加"商品管理"顶级菜单 + "分类管理"子菜单，具体字段参考现有 Menu 初始化逻辑：顶级菜单 `product-manage` path `/product`、子菜单 `product-category` path `/product/category`，permission 对应 `product:category:*`。避免和现有初始化顺序耦合，初始化器保持幂等即可（按 `permissionCode` 查重后插入）。

- [ ] **Step 4: 跑 ProductIntegrationTest 最小用例**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest#admin_category_tree_should_require_login`
Expected: PASS（401）

- [ ] **Step 5: 提交后台分类接口**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java
git commit -m "feat: 后台商品分类接口 + 操作审计 + 基础集成测试"
```

---

### Task 6：前台分类接口（AppCategoryController）

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/app/AppCategoryController.java`
- Test: 继续在 `ProductIntegrationTest` 加前台树用例

- [ ] **Step 1: 写前台树接口集成测试**

在 ProductIntegrationTest 追加：

```java
@Test
void app_category_tree_should_public() throws Exception {
    mockMvc.perform(get("/api/categories/tree")
                    .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: 跑测试验证 404**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest#app_category_tree_should_public`
Expected: FAIL（404）

- [ ] **Step 3: 实现 AppCategoryController**

```java
package com.springshop.product.controller.app;

import com.springshop.common.result.Result;
import com.springshop.product.category.service.CategoryService;
import com.springshop.product.category.vo.CategoryNodeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台商品分类接口
 */
@Tag(name = "前台商品分类")
@RestController
@RequestMapping("/api/categories")
public class AppCategoryController {

    private final CategoryService categoryService;

    public AppCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "获取启用分类树")
    @GetMapping("/tree")
    public Result<List<CategoryNodeVO>> tree() {
        return Result.success(categoryService.tree());
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest#app_category_tree_should_public`
Expected: PASS

- [ ] **Step 5: 提交前台分类接口**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/controller/app/AppCategoryController.java spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java
git commit -m "feat: 前台商品分类树接口"
```

---

### Task 7：商品（SPU/SKU/Image）实体与 Mapper

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/ProductSku.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/entity/ProductImage.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductSkuMapper.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductImageMapper.java`

- [ ] **Step 1: 空步骤（本任务为持久层准备，无独立测试）**

跳过，下一步 Step 4 会通过 compile 兜底。

- [ ] **Step 2: 跑 compile 基线**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 实现实体与 Mapper**

`ProductSku.java`

```java
package com.springshop.product.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 实体
 */
@TableName("product_sku")
public class ProductSku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String skuCode;

    private String specs;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer stock;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    @Version
    private Integer version;

    public ProductSku() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
```

`ProductImage.java`

```java
package com.springshop.product.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 商品图片实体
 */
@TableName("product_image")
public class ProductImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String imageUrl;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    @Version
    private Integer version;

    public ProductImage() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
```

`ProductSkuMapper.java`

```java
package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {
}
```

`ProductImageMapper.java`

```java
package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductImage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductImageMapper extends BaseMapper<ProductImage> {
}
```

- [ ] **Step 4: compile 校验**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交商品持久层**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/product/entity/ spring-shop-product/src/main/java/com/springshop/product/product/mapper/
git commit -m "feat: SPU/SKU/Image 实体与 Mapper 落地"
```

---

### Task 8：商品写服务单测 + ProductManageServiceImpl（保存/修改/上下架）

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductSaveRequest.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductSkuItem.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductImageItem.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/ProductManageService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductManageServiceImpl.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductManageServiceImplTest.java`

- [ ] **Step 1: 写失败单测**

`ProductManageServiceImplTest.java`

```java
package com.springshop.product.product.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductImageItem;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductManageServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductImageMapper imageMapper;
    @Mock
    private ProductCategoryMapper categoryMapper;

    @InjectMocks
    private ProductManageServiceImpl manageService;

    private ProductSaveRequest request;

    @BeforeEach
    void setUp() {
        request = new ProductSaveRequest();
        request.setCategoryId(1L);
        request.setName("iPhone");
        request.setSubtitle("新品");
        request.setMainImage("http://a.com/1.jpg");
        request.setDetail("详情");
        request.setStatus(1);

        ProductSkuItem sku = new ProductSkuItem();
        sku.setSkuCode("SKU-001");
        sku.setSpecs("颜色:黑;尺寸:L");
        sku.setPrice(new BigDecimal("9999.00"));
        sku.setOriginalPrice(new BigDecimal("10999.00"));
        sku.setStock(100);
        sku.setStatus(1);
        request.setSkus(List.of(sku));

        ProductImageItem image = new ProductImageItem();
        image.setImageUrl("http://a.com/1.jpg");
        image.setSort(1);
        request.setImages(List.of(image));
    }

    @Test
    void should_create_throw_when_skus_empty() {
        request.setSkus(List.of());
        BusinessException ex = assertThrows(BusinessException.class, () -> manageService.create(request));
        assertEquals(ResultCode.PRODUCT_SKU_EMPTY.getCode(), ex.getCode());
    }

    @Test
    void should_create_throw_when_category_missing() {
        when(categoryMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> manageService.create(request));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_create_success() {
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());
        when(productMapper.insert(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        });
        when(skuMapper.insert(any(ProductSku.class))).thenReturn(1);
        when(imageMapper.insert(any(ProductImage.class))).thenReturn(1);

        assertDoesNotThrow(() -> manageService.create(request));

        verify(productMapper, times(1)).insert(any(Product.class));
        verify(skuMapper, times(1)).insert(any(ProductSku.class));
        verify(imageMapper, times(1)).insert(any(ProductImage.class));
    }

    @Test
    void should_off_shelf_throw_when_missing() {
        when(productMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> manageService.updateStatus(1L, 0));
        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }
}
```

- [ ] **Step 2: 跑单测，应该编译缺类 / 缺方法失败**

Run: `mvn -pl spring-shop-product test -Dtest=ProductManageServiceImplTest`
Expected: FAIL

- [ ] **Step 3: 实现 DTO + 接口 + 实现**

`ProductSkuItem.java`

```java
package com.springshop.product.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 保存商品时的 SKU 子项
 */
public class ProductSkuItem {

    private Long id;

    @NotBlank(message = "SKU 编码不能为空")
    private String skuCode;

    private String specs;

    @NotNull(message = "销售价不能为空")
    private BigDecimal price;

    private BigDecimal originalPrice;

    @NotNull(message = "库存不能为空")
    private Integer stock;

    @NotNull(message = "SKU 状态不能为空")
    private Integer status;

    public ProductSkuItem() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
```

`ProductImageItem.java`

```java
package com.springshop.product.product.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存商品时的图片子项
 */
public class ProductImageItem {

    private Long id;

    @NotBlank(message = "图片 URL 不能为空")
    private String imageUrl;

    private Integer sort;

    public ProductImageItem() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
```

`ProductSaveRequest.java`

```java
package com.springshop.product.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 保存商品（SPU + SKU + 图片）入参
 */
public class ProductSaveRequest {

    @NotNull(message = "分类 id 不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String subtitle;

    private String mainImage;

    private String detail;

    @NotNull(message = "商品状态不能为空")
    private Integer status;

    @Valid
    @NotEmpty(message = "至少需要一个 SKU")
    private List<ProductSkuItem> skus;

    private List<ProductImageItem> images;

    public ProductSaveRequest() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<ProductSkuItem> getSkus() { return skus; }
    public void setSkus(List<ProductSkuItem> skus) { this.skus = skus; }
    public List<ProductImageItem> getImages() { return images; }
    public void setImages(List<ProductImageItem> images) { this.images = images; }
}
```

`ProductManageService.java`

```java
package com.springshop.product.product.service;

import com.springshop.product.product.dto.ProductSaveRequest;

/**
 * 商品写服务（聚合 SPU/SKU/Image）
 */
public interface ProductManageService {

    void create(ProductSaveRequest request);

    void update(Long id, ProductSaveRequest request);

    void updateStatus(Long id, Integer status);
}
```

`ProductManageServiceImpl.java`

```java
package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductImageItem;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductManageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 商品写服务实现（同事务内落 SPU/SKU/Image）
 */
@Service
public class ProductManageServiceImpl implements ProductManageService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductImageMapper imageMapper;
    private final ProductCategoryMapper categoryMapper;

    public ProductManageServiceImpl(ProductMapper productMapper,
                                    ProductSkuMapper skuMapper,
                                    ProductImageMapper imageMapper,
                                    ProductCategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.categoryMapper = categoryMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void create(ProductSaveRequest request) {
        validateRequest(request);
        ProductCategory category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        Product product = new Product();
        applyProduct(product, request);
        if (product.getSales() == null) {
            product.setSales(0);
        }
        productMapper.insert(product);

        Long productId = product.getId();
        saveSkus(productId, request.getSkus(), null);
        saveImages(productId, request.getImages(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void update(Long id, ProductSaveRequest request) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        validateRequest(request);
        ProductCategory category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
        applyProduct(product, request);
        productMapper.updateById(product);

        saveSkus(id, request.getSkus(), existingSkuIds(id));
        saveImages(id, request.getImages(), existingImageIds(id));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateStatus(Long id, Integer status) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        product.setStatus(status);
        productMapper.updateById(product);
    }

    private void validateRequest(ProductSaveRequest request) {
        if (request.getSkus() == null || request.getSkus().isEmpty()) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_EMPTY);
        }
        Set<String> codes = new HashSet<>();
        for (ProductSkuItem skuItem : request.getSkus()) {
            if (!codes.add(skuItem.getSkuCode())) {
                throw new BusinessException(ResultCode.PRODUCT_SKU_CODE_DUPLICATE);
            }
        }
        // 全库检查 SKU 编码唯一性（忽略逻辑删除，MP 自动带 is_deleted 条件）
        List<String> list = request.getSkus().stream().map(ProductSkuItem::getSkuCode).toList();
        Long dup = skuMapper.selectCount(
                Wrappers.<ProductSku>lambdaQuery().in(ProductSku::getSkuCode, list));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_CODE_DUPLICATE);
        }
    }

    private void applyProduct(Product product, ProductSaveRequest req) {
        product.setCategoryId(req.getCategoryId());
        product.setName(req.getName());
        product.setSubtitle(req.getSubtitle());
        product.setMainImage(req.getMainImage());
        product.setDetail(req.getDetail());
        product.setStatus(req.getStatus());
    }

    private void saveSkus(Long productId, List<ProductSkuItem> items, Set<Long> oldIds) {
        if (items == null) {
            return;
        }
        for (ProductSkuItem item : items) {
            ProductSku sku;
            if (item.getId() != null) {
                sku = skuMapper.selectById(item.getId());
                if (sku == null) {
                    throw new BusinessException(ResultCode.PRODUCT_SKU_NOT_FOUND);
                }
                if (oldIds != null) {
                    oldIds.remove(item.getId());
                }
            } else {
                sku = new ProductSku();
                sku.setProductId(productId);
            }
            sku.setSkuCode(item.getSkuCode());
            sku.setSpecs(item.getSpecs());
            sku.setPrice(item.getPrice());
            sku.setOriginalPrice(item.getOriginalPrice());
            sku.setStock(item.getStock());
            sku.setStatus(item.getStatus());
            if (item.getId() == null) {
                skuMapper.insert(sku);
            } else {
                skuMapper.updateById(sku);
            }
        }
        if (oldIds != null && !oldIds.isEmpty()) {
            oldIds.forEach(skuMapper::deleteById);
        }
    }

    private void saveImages(Long productId, List<ProductImageItem> items, Set<Long> oldIds) {
        if (items == null) {
            return;
        }
        for (ProductImageItem item : items) {
            ProductImage img;
            if (item.getId() != null) {
                img = imageMapper.selectById(item.getId());
                if (img == null) {
                    throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
                }
                if (oldIds != null) {
                    oldIds.remove(item.getId());
                }
            } else {
                img = new ProductImage();
                img.setProductId(productId);
            }
            img.setImageUrl(item.getImageUrl());
            img.setSort(item.getSort() == null ? 0 : item.getSort());
            if (item.getId() == null) {
                imageMapper.insert(img);
            } else {
                imageMapper.updateById(img);
            }
        }
        if (oldIds != null && !oldIds.isEmpty()) {
            oldIds.forEach(imageMapper::deleteById);
        }
    }

    private Set<Long> existingSkuIds(Long productId) {
        return new HashSet<>(skuMapper.selectList(
                        Wrappers.<ProductSku>lambdaQuery().eq(ProductSku::getProductId, productId))
                .stream().map(ProductSku::getId).toList());
    }

    private Set<Long> existingImageIds(Long productId) {
        return new HashSet<>(imageMapper.selectList(
                        Wrappers.<ProductImage>lambdaQuery().eq(ProductImage::getProductId, productId))
                .stream().map(ProductImage::getId).toList());
    }
}
```

- [ ] **Step 4: 跑单测**

Run: `mvn -pl spring-shop-product test -Dtest=ProductManageServiceImplTest`
Expected: Tests run: 4, Failures: 0, Errors: 0

- [ ] **Step 5: 提交商品写服务**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/product/ spring-shop-product/src/test/java/com/springshop/product/product/service/impl/
git commit -m "feat: 商品写服务 ProductManageService 落地并补单测"
```

---

### Task 9：商品查询服务单测 + 实现（后台详情 + 前台列表 + 前台详情）

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/dto/ProductPageQuery.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductListVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductDetailVO.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/ProductQueryService.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductQueryServiceImpl.java`
- Test: `spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductQueryServiceImplTest.java`

- [ ] **Step 1: 写失败单测**

```java
package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ProductImageMapper imageMapper;
    @Mock
    private ProductCategoryMapper categoryMapper;

    @InjectMocks
    private ProductQueryServiceImpl queryService;

    private Product product;
    private ProductSku sku;
    private ProductImage image;
    private ProductCategory category;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(1L);
        product.setCategoryId(10L);
        product.setName("iPhone");
        product.setSubtitle("新品");
        product.setMainImage("http://a.com/1.jpg");
        product.setDetail("详情");
        product.setSales(10);
        product.setStatus(1);

        sku = new ProductSku();
        sku.setId(1L);
        sku.setProductId(1L);
        sku.setSkuCode("SKU-001");
        sku.setPrice(new BigDecimal("9999.00"));
        sku.setStock(100);
        sku.setStatus(1);

        image = new ProductImage();
        image.setId(1L);
        image.setProductId(1L);
        image.setImageUrl("http://a.com/1.jpg");
        image.setSort(1);

        category = new ProductCategory();
        category.setId(10L);
        category.setName("数码");
    }

    @Test
    void should_detail_throw_when_missing() {
        when(productMapper.selectById(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> queryService.detail(1L));
        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_app_detail_throw_when_off_shelf() {
        product.setStatus(0);
        when(productMapper.selectById(1L)).thenReturn(product);
        BusinessException ex = assertThrows(BusinessException.class, () -> queryService.appDetail(1L));
        assertEquals(ResultCode.PRODUCT_OFF_SHELF.getCode(), ex.getCode());
    }

    @Test
    void should_detail_assemble_success() {
        when(productMapper.selectById(1L)).thenReturn(product);
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(imageMapper.selectList(any())).thenReturn(List.of(image));
        when(categoryMapper.selectById(10L)).thenReturn(category);

        ProductDetailVO vo = queryService.detail(1L);
        assertEquals(1L, vo.getId());
        assertEquals("数码", vo.getCategoryName());
        assertEquals(1, vo.getSkus().size());
        assertEquals(1, vo.getImages().size());
        assertEquals(new BigDecimal("9999.00"), vo.getMinPrice());
    }

    @Test
    void should_page_success() {
        ProductPageQuery q = new ProductPageQuery();
        q.setPageNum(1L);
        q.setPageSize(10L);

        IPage<Product> page = new Page<>(1, 10);
        page.setRecords(List.of(product));
        page.setTotal(1);
        when(productMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ProductListVO> res = queryService.page(q);
        assertEquals(1, res.getList().size());
        assertEquals(1L, res.getTotal());
    }
}
```

- [ ] **Step 2: 跑测试，编译失败**

Run: `mvn -pl spring-shop-product test -Dtest=ProductQueryServiceImplTest`
Expected: FAIL（缺 DTO/VO/服务类）

- [ ] **Step 3: 实现 DTO/VO + 查询服务**

`ProductPageQuery.java`

```java
package com.springshop.product.product.dto;

import com.springshop.common.dto.PageQuery;

/**
 * 商品分页查询入参
 */
public class ProductPageQuery extends PageQuery {

    private Long categoryId;

    private String keyword;

    private Integer status;

    public ProductPageQuery() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
```

`ProductListVO.java`

```java
package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 商品列表项 VO
 */
@Schema(description = "商品列表项")
public class ProductListVO {

    @Schema(description = "SPU id")
    private Long id;

    @Schema(description = "分类 id")
    private Long categoryId;

    @Schema(description = "分类名")
    private String categoryName;

    @Schema(description = "商品名")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "最低售价")
    private BigDecimal minPrice;

    @Schema(description = "销量")
    private Integer sales;

    @Schema(description = "上下架状态")
    private Integer status;

    public ProductListVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
```

`ProductDetailVO.java`

```java
package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情 VO
 */
@Schema(description = "商品详情")
public class ProductDetailVO {

    public static class SkuVO {
        private Long id;
        private String skuCode;
        private String specs;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private Integer stock;
        private Integer status;

        public SkuVO() {
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSkuCode() { return skuCode; }
        public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
        public String getSpecs() { return specs; }
        public void setSpecs(String specs) { this.specs = specs; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public BigDecimal getOriginalPrice() { return originalPrice; }
        public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
    }

    public static class ImageVO {
        private Long id;
        private String imageUrl;
        private Integer sort;

        public ImageVO() {
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
        public Integer getSort() { return sort; }
        public void setSort(Integer sort) { this.sort = sort; }
    }

    @Schema(description = "SPU id")
    private Long id;

    @Schema(description = "分类 id")
    private Long categoryId;

    @Schema(description = "分类名")
    private String categoryName;

    @Schema(description = "商品名")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "详情")
    private String detail;

    @Schema(description = "最低售价")
    private BigDecimal minPrice;

    @Schema(description = "销量")
    private Integer sales;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "SKU 列表")
    private List<SkuVO> skus;

    @Schema(description = "图片列表")
    private List<ImageVO> images;

    public ProductDetailVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<SkuVO> getSkus() { return skus; }
    public void setSkus(List<SkuVO> skus) { this.skus = skus; }
    public List<ImageVO> getImages() { return images; }
    public void setImages(List<ImageVO> images) { this.images = images; }
}
```

`ProductQueryService.java`

```java
package com.springshop.product.product.service;

import com.springshop.common.result.PageResult;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;

/**
 * 商品查询服务
 */
public interface ProductQueryService {

    PageResult<ProductListVO> page(ProductPageQuery query);

    ProductDetailVO detail(Long id);

    ProductDetailVO appDetail(Long id);
}
```

`ProductQueryServiceImpl.java`

```java
package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品查询服务实现（前台列表/详情 + 后台详情共用聚合）
 */
@Service
public class ProductQueryServiceImpl implements ProductQueryService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductImageMapper imageMapper;
    private final ProductCategoryMapper categoryMapper;

    public ProductQueryServiceImpl(ProductMapper productMapper,
                                   ProductSkuMapper skuMapper,
                                   ProductImageMapper imageMapper,
                                   ProductCategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public PageResult<ProductListVO> page(ProductPageQuery query) {
        Page<Product> page = new Page<>(
                query.getPageNum() == null ? 1 : query.getPageNum(),
                query.getPageSize() == null ? 10 : query.getPageSize());
        IPage<Product> p = productMapper.selectPage(page,
                Wrappers.<Product>lambdaQuery()
                        .eq(query.getCategoryId() != null, Product::getCategoryId, query.getCategoryId())
                        .eq(query.getStatus() != null, Product::getStatus, query.getStatus())
                        .like(StringUtils.hasText(query.getKeyword()), Product::getName, query.getKeyword())
                        .orderByDesc(Product::getCreateTime));

        List<Long> productIds = p.getRecords().stream().map(Product::getId).toList();
        Map<Long, List<ProductSku>> skuMap = groupByProductId(skuMapper, productIds);
        Map<Long, String> categoryNameMap = fetchCategoryNames(p.getRecords().stream()
                .map(Product::getCategoryId).distinct().toList());

        List<ProductListVO> list = p.getRecords().stream().map(spu -> {
            ProductListVO vo = new ProductListVO();
            vo.setId(spu.getId());
            vo.setCategoryId(spu.getCategoryId());
            vo.setCategoryName(categoryNameMap.getOrDefault(spu.getCategoryId(), null));
            vo.setName(spu.getName());
            vo.setSubtitle(spu.getSubtitle());
            vo.setMainImage(spu.getMainImage());
            vo.setSales(spu.getSales());
            vo.setStatus(spu.getStatus());
            vo.setMinPrice(minPrice(skuMap.get(spu.getId())));
            return vo;
        }).collect(Collectors.toList());

        return new PageResult<>(list, p.getTotal(), p.getPages());
    }

    @Override
    public ProductDetailVO detail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return assemble(product);
    }

    @Override
    public ProductDetailVO appDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return assemble(product);
    }

    private ProductDetailVO assemble(Product product) {
        List<ProductSku> skus = skuMapper.selectList(
                Wrappers.<ProductSku>lambdaQuery().eq(ProductSku::getProductId, product.getId())
                        .orderByAsc(ProductSku::getId));
        List<ProductImage> images = imageMapper.selectList(
                Wrappers.<ProductImage>lambdaQuery().eq(ProductImage::getProductId, product.getId())
                        .orderByAsc(ProductImage::getSort));

        ProductCategory category = product.getCategoryId() == null ? null
                : categoryMapper.selectById(product.getCategoryId());

        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(category == null ? null : category.getName());
        vo.setName(product.getName());
        vo.setSubtitle(product.getSubtitle());
        vo.setMainImage(product.getMainImage());
        vo.setDetail(product.getDetail());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        vo.setMinPrice(minPrice(skus));

        vo.setSkus(skus.stream().map(s -> {
            ProductDetailVO.SkuVO sku = new ProductDetailVO.SkuVO();
            sku.setId(s.getId());
            sku.setSkuCode(s.getSkuCode());
            sku.setSpecs(s.getSpecs());
            sku.setPrice(s.getPrice());
            sku.setOriginalPrice(s.getOriginalPrice());
            sku.setStock(s.getStock());
            sku.setStatus(s.getStatus());
            return sku;
        }).collect(Collectors.toList()));

        vo.setImages(images.stream().map(i -> {
            ProductDetailVO.ImageVO img = new ProductDetailVO.ImageVO();
            img.setId(i.getId());
            img.setImageUrl(i.getImageUrl());
            img.setSort(i.getSort());
            return img;
        }).collect(Collectors.toList()));
        return vo;
    }

    private Map<Long, List<ProductSku>> groupByProductId(ProductSkuMapper mapper, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                        .in(ProductSku::getProductId, productIds))
                .stream().collect(Collectors.groupingBy(ProductSku::getProductId));
    }

    private Map<Long, String> fetchCategoryNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getName, (a, b) -> a));
    }

    private BigDecimal minPrice(List<ProductSku> skus) {
        if (skus == null || skus.isEmpty()) {
            return null;
        }
        return skus.stream()
                .filter(s -> s.getPrice() != null)
                .min(Comparator.comparing(ProductSku::getPrice))
                .map(ProductSku::getPrice)
                .orElse(null);
    }
}
```

- [ ] **Step 4: 跑单测**

Run: `mvn -pl spring-shop-product test -Dtest=ProductQueryServiceImplTest`
Expected: Tests run: 4, Failures: 0, Errors: 0

- [ ] **Step 5: 提交查询服务**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/product/ spring-shop-product/src/test/java/com/springshop/product/product/service/impl/
git commit -m "feat: 商品查询服务 ProductQueryService 落地并补单测"
```

---

### Task 10：后台商品管理接口 + 前台商品接口 + 全量集成测试补全

**Files:**
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java`
- Create: `spring-shop-product/src/main/java/com/springshop/product/controller/app/AppProductController.java`
- Modify: `spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java`
- Modify: `spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java`（补商品管理菜单权限：`product:product:*`）

- [ ] **Step 1: 先补集成测试：未登录访问后台商品分页应 401；前台列表应 200；前台详情下架应业务码**

（示例）在 ProductIntegrationTest 中继续追加：

```java
@Test
void admin_product_page_should_require_login() throws Exception {
    mockMvc.perform(get("/api/admin/products")
                    .param("pageNum", "1")
                    .param("pageSize", "10"))
            .andExpect(status().isUnauthorized());
}

@Test
void app_product_list_should_ok() throws Exception {
    mockMvc.perform(get("/api/products")
                    .param("pageNum", "1")
                    .param("pageSize", "10"))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: 跑集成测试**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest`
Expected: FAIL（缺对应 Controller）

- [ ] **Step 3: 实现前后台 Controller**

`AdminProductController.java`

```java
package com.springshop.product.controller.admin;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 后台商品管理接口
 */
@Tag(name = "后台商品管理")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductManageService manageService;
    private final ProductQueryService queryService;

    public AdminProductController(ProductManageService manageService,
                                  ProductQueryService queryService) {
        this.manageService = manageService;
        this.queryService = queryService;
    }

    @Operation(summary = "商品分页")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping
    public Result<PageResult<ProductListVO>> page(ProductPageQuery query) {
        return Result.success(queryService.page(query));
    }

    @Operation(summary = "商品详情")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(queryService.detail(id));
    }

    @Operation(summary = "新增商品")
    @OperationLog(module = "商品管理", operation = "新增商品")
    @PreAuthorize("hasAuthority('product:product:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ProductSaveRequest request) {
        manageService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改商品")
    @OperationLog(module = "商品管理", operation = "修改商品")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        manageService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "上下架")
    @OperationLog(module = "商品管理", operation = "商品上下架")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        manageService.updateStatus(id, status);
        return Result.success();
    }
}
```

`AppProductController.java`

```java
package com.springshop.product.controller.app;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 前台商品查询接口
 */
@Tag(name = "前台商品")
@RestController
@RequestMapping("/api/products")
public class AppProductController {

    private final ProductQueryService queryService;

    public AppProductController(ProductQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "商品列表（仅上架）")
    @GetMapping
    public Result<PageResult<ProductListVO>> page(ProductPageQuery query) {
        query.setStatus(1);
        return Result.success(queryService.page(query));
    }

    @Operation(summary = "商品详情（仅上架）")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(queryService.appDetail(id));
    }
}
```

- [ ] **Step 4: 跑 ProductIntegrationTest 所有用例**

Run: `mvn -pl spring-shop-web test -Dtest=ProductIntegrationTest`
Expected: PASS

- [ ] **Step 5: 提交接口层与集成测试**

```bash
git add spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java spring-shop-product/src/main/java/com/springshop/product/controller/app/AppProductController.java spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java
git commit -m "feat: 后台/前台商品接口 + ProductIntegrationTest 完成"
```

---

### Task 11：全量回归测试 + 模块文档

**Files:**
- Create: `docs/product-module.md`

- [ ] **Step 1: 写 docs 内容草稿**

`docs/product-module.md` 至少包含：
- 模块目标与范围（MVP 能力列表）
- 模块包结构（category / product / controller.admin / controller.app）
- 领域拆分说明：写服务 `ProductManageService` / 读服务 `ProductQueryService` / `CategoryService`
- 接口清单：后台分类、后台商品、前台分类、前台商品
- 权限码清单：`product:category:*` 与 `product:product:*`
- 关键业务规则（SKU 唯一、分类删除校验、下架商品前台拒绝访问）
- 测试说明（单测模块 + ProductIntegrationTest 运行方法）

- [ ] **Step 2: 跑全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，所有测试全绿

- [ ] **Step 3: 起 web 服务验证健康检查**

（本地 MySQL/Redis 可选；如果没配置就跳过，单靠集成测试兜底）
Run: `mvn -pl spring-shop-web -am spring-boot:run`
另终端：`curl http://localhost:6001/api/health`
Expected: 返回 code == 200

- [ ] **Step 4: 再次跑全量测试确保服务关闭后 CI 仍绿**

Run: `mvn test`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交文档并收尾**

```bash
git add docs/product-module.md
git commit -m "docs: 新增商品模块技术梳理文档"
```

---

## 三、Self-Review

**1. Spec coverage 对照**

- 后台分类 CRUD + 树：Task 4 / Task 5 ✅
- 前台分类树：Task 6 ✅
- SPU/SKU/Image 持久层：Task 3 + Task 7 ✅
- 后台商品写服务（创建/修改/上下架）：Task 8 ✅
- 前后台商品查询（分页/详情/上下架校验）：Task 9 / Task 10 ✅
- RBAC 权限码 + 操作审计：Task 5 / Task 10 + AdminDataInitializer 初始化菜单 ✅
- 单元测试 + H2 集成测试：Task 4 / Task 8 / Task 9 / Task 11 + ProductIntegrationTest ✅
- 模块文档：Task 11 ✅
- 错误码段位：Task 2（2000~2099）✅
- 模块注册：Task 1 ✅
- 未越界改动 common 核心公共能力（仅 ResultCode 扩展）✅

**2. Placeholder scan**

- 每个 Task 的 Step 1/3 都给出可拷贝的真实代码块 ✅
- 无 "TBD / TODO / handle edge cases" 这类占位 ✅
- 命令与预期输出均显式给出 ✅
- 权限码在 Controller 中使用字符串常量形式，未用占位描述 ✅

**3. Type consistency（类型一致性检查）**

- DTO 字段：`ProductSaveRequest.categoryId` ↔ `Product.categoryId` ↔ 查询 `ProductPageQuery.categoryId` 类型一致 `Long` ✅
- VO 枚举：所有 VO 的 `status` / `sales` / `sort` 类型与 entity 保持一致 ✅
- 金额类型：SKU.price / originalPrice / ProductListVO.minPrice 均 `BigDecimal` ✅
- 服务方法签名：`detail(Long id)` / `appDetail(Long id)` 在接口、实现、调用方一致 ✅
- 错误码引用：`PRODUCT_CATEGORY_NOT_FOUND / PRODUCT_NOT_FOUND / PRODUCT_SKU_EMPTY / PRODUCT_SKU_CODE_DUPLICATE / PRODUCT_OFF_SHELF` 与枚举扩展一致 ✅
- 分页入参：`ProductPageQuery extends PageQuery`，与 web 统一分页约定一致 ✅
