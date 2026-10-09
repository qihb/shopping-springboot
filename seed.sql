-- ============================================================
-- spring_shop 测试数据种子脚本（仅本地开发使用，禁止用于生产）
-- 测试账号密码统一为：123456（BCrypt 加密）
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 spring_shop < seed.sql
-- 重复执行前请先执行文末「清理语句」
-- ============================================================

-- ---------- 前台用户 ----------
-- 已有：zhangsan(1)、lisi(2)，新增 wangwu(3)
INSERT INTO `user` (`username`, `password`, `nickname`, `phone`, `status`) VALUES
('wangwu', '$2a$10$1aWdi49mkcBiPTnICwMOau5jTz.fE26cMypXVWxzD6VaTfxAvTfCK', '王五', '13800000003', 1);

-- ---------- 商品分类（顶级 + 二级）----------
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort`, `status`) VALUES
(1, 0, '手机数码', 1, 1),
(2, 0, '电脑办公', 2, 1),
(3, 0, '家用电器', 3, 1),
(4, 0, '食品生鲜', 4, 1),
(5, 1, '智能手机', 1, 1),
(6, 1, '智能穿戴', 2, 1),
(7, 2, '笔记本电脑', 1, 1),
(8, 2, '电脑外设', 2, 1),
(9, 3, '厨房电器', 1, 1),
(10, 4, '休闲零食', 1, 1);

-- ---------- 商品（SPU）----------
INSERT INTO `product` (`id`, `category_id`, `name`, `subtitle`, `main_image`, `detail`, `sales`, `status`) VALUES
(1, 5, 'Star X1 Pro 旗舰手机', '第二代骁龙8旗舰芯 | 2K E6屏 | 120W秒充', 'https://picsum.photos/seed/p1/800/800', '<p>第二代骁龙8旗舰芯片，2K 120Hz E6 直屏，120W 有线秒充，5000mAh 大电池。</p>', 1523, 1),
(2, 5, 'AirPods Pro 3 无线降噪耳机', '主动降噪 | 空间音频 | 30小时续航', 'https://picsum.photos/seed/p2/800/800', '<p>主动降噪升级，空间音频，单次 6 小时 / 配盒 30 小时续航。</p>', 8762, 1),
(3, 6, 'Watch S4 智能手表', '血氧监测 | 14天续航 | 100+运动模式', 'https://picsum.photos/seed/p3/800/800', '<p>1.43 英寸 AMOLED 表盘，血氧/心率/睡眠监测，14 天长续航。</p>', 2210, 1),
(4, 7, 'ThinkPad T14p 2026 商务本', '14代i7 | 32G+1T | 2.8K 120Hz', 'https://picsum.photos/seed/p4/800/800', '<p>14 代酷睿 i7 处理器，32G 内存 + 1T 固态，2.8K 120Hz 低蓝光屏。</p>', 645, 1),
(5, 8, '罗技 MX Master 4S 无线鼠标', '8K DPI | 静音按键 | 多设备流转', 'https://picsum.photos/seed/p5/800/800', '<p>8000 DPI 传感器，电磁滚轮，三设备无缝切换，适合多屏办公。</p>', 4321, 1),
(6, 8, '机械师 K87 三模机械键盘', 'Gasket结构 | 三模连接 | PBT键帽', 'https://picsum.photos/seed/p6/800/800', '<p>Gasket 结构，蓝牙/2.4G/有线三模，全键无冲，PBT 热升华键帽。</p>', 1877, 1),
(7, 9, '云米智能电饭煲 4L', '24小时预约 | IH电磁加热 | 低糖模式', 'https://picsum.photos/seed/p7/800/800', '<p>IH 电磁环绕加热，24 小时预约，低糖胆模式，4L 容量满足全家。</p>', 3092, 1),
(8, 9, '飞利浦电动牙刷 HX9954', '声波震动 | 4种模式 | 智能计时', 'https://picsum.photos/seed/p8/800/800', '<p>声波震动每分钟 62000 次，4 种清洁模式，智能计时提醒。</p>', 5540, 1),
(9, 10, '三只松鼠 每日坚果 750g', '30包混合装 | 锁鲜工艺', 'https://picsum.photos/seed/p9/800/800', '<p>6 种坚果 + 3 种果干科学配比，独立小包锁鲜，30 天装。</p>', 9901, 1),
(10, 10, '百草味 每日黑麦面包 1kg', '全麦0蔗糖 | 早餐代餐', 'https://picsum.photos/seed/p10/800/800', '<p>全麦黑麦配方，0 蔗糖添加，独立包装，早餐代餐首选。</p>', 3320, 1);

-- ---------- 商品 SKU ----------
-- 注意：这里没有 stock 列。库存自 V9 起归 inventory 表，product_sku.stock 这个迁移期镜像
-- 列已由 V10 删除；库存数据见下方「库存」段。
INSERT INTO `product_sku` (`id`, `product_id`, `sku_code`, `specs`, `price`, `original_price`, `status`) VALUES
(1,  1, 'SKU-STARX1-BLK-12256', '颜色:曜石黑;版本:12+256G', 5999.00, 6499.00, 1),
(2,  1, 'SKU-STARX1-BLU-12256', '颜色:冰川蓝;版本:12+256G', 5999.00, 6499.00, 1),
(3,  1, 'SKU-STARX1-BLK-16512', '颜色:曜石黑;版本:16+512G', 6999.00, 7599.00, 1),
(4,  2, 'SKU-APP-APP3-STD',      '规格:标准版',              1899.00, 1999.00, 1),
(5,  3, 'SKU-WATCHS4-BLK',       '表带:黑色氟橡胶',          1299.00, 1499.00, 1),
(6,  3, 'SKU-WATCHS4-BRN',       '表带:棕色真皮',            1399.00, 1599.00, 1),
(7,  4, 'SKU-TP-T14P-I7-32-1T',  '配置:i7/32G/1T',           8499.00, 8999.00, 1),
(8,  5, 'SKU-MX4S-GRAPHITE',     '颜色:石墨黑',              799.00,  899.00,  1),
(9,  6, 'SKU-K87-BLK-RED',       '颜色:黑;轴体:红轴',        399.00,  459.00,  1),
(10, 6, 'SKU-K87-WHT-BRN',       '颜色:白;轴体:茶轴',        399.00,  459.00,  1),
(11, 7, 'SKU-FB-4L-IH',          '容量:4L',                  299.00,  399.00,  1),
(12, 8, 'SKU-PHILIPS-HX9954',    '颜色:粉白',                699.00,  899.00,  1),
(13, 9, 'SKU-SSS-NUT-750',       '规格:750g/30包',           79.00,   99.00,   1),
(14, 10, 'SKU-BCW-BREAD-1K',     '规格:1kg',                 39.90,   49.90,   1);

-- ---------- 库存（V9 起库存的唯一来源） ----------
-- 每个 SKU 都必须有一行 inventory，否则下单时会被判为「库存不足」（库存行缺失）。
-- locked_stock 一律 0：种子数据没有未付款订单。
INSERT INTO `inventory` (`sku_id`, `stock`, `locked_stock`) VALUES
(1,  200,  0),
(2,  180,  0),
(3,  80,   0),
(4,  500,  0),
(5,  300,  0),
(6,  150,  0),
(7,  60,   0),
(8,  400,  0),
(9,  260,  0),
(10, 240,  0),
(11, 350,  0),
(12, 280,  0),
(13, 1000, 0),
(14, 800,  0);

-- 库存变更流水：与 InventoryService.initStock 同口径，初始化补一条 change_type=5 的流水，
-- 这样「流水最后一条的 stock_after == inventory.stock」这条对账不变量从种子数据起就成立
INSERT INTO `inventory_log` (`sku_id`, `product_id`, `change_type`, `stock_before`, `stock_after`,
                             `locked_before`, `locked_after`, `remark`)
SELECT `sku_id`, s.`product_id`, 5, 0, `stock`, 0, 0, '种子数据初始化'
FROM `inventory` i
JOIN `product_sku` s ON s.`id` = i.`sku_id`;

-- ---------- 商品图片（详情图集）----------
INSERT INTO `product_image` (`product_id`, `image_url`, `sort`) VALUES
(1, 'https://picsum.photos/seed/p1a/800/800', 0),
(1, 'https://picsum.photos/seed/p1b/800/800', 1),
(2, 'https://picsum.photos/seed/p2a/800/800', 0),
(4, 'https://picsum.photos/seed/p4a/800/800', 0),
(7, 'https://picsum.photos/seed/p7a/800/800', 0),
(9, 'https://picsum.photos/seed/p9a/800/800', 0);

-- ---------- 收货地址 ----------
INSERT INTO `shipping_address` (`user_id`, `receiver_name`, `receiver_phone`, `province`, `city`, `district`, `detail_address`, `is_default`) VALUES
(1, '张三', '13800000001', '广东省', '深圳市', '南山区', '科技园南路 88 号 3 栋 501', 1),
(2, '李四', '13800000002', '浙江省', '杭州市', '西湖区', '文三路 199 号 创业大厦 12F', 1),
(3, '王五', '13800000003', '北京市', '北京市', '海淀区', '中关村大街 1 号 A 座 1808', 1);

-- ---------- 购物车 ----------
INSERT INTO `cart_item` (`user_id`, `sku_id`, `quantity`, `checked`) VALUES
(1, 1, 1, 1),
(1, 13, 2, 1),
(2, 7, 1, 1);

-- ---------- 订单（快照数据 + 明细）----------
INSERT INTO `orders` (`id`, `order_no`, `user_id`, `total_amount`, `pay_amount`, `status`, `receiver_name`, `receiver_phone`, `receiver_address`, `remark`, `pay_time`, `ship_time`, `finish_time`, `cancel_time`) VALUES
(1, 'SO20260930000001', 1, 6157.00, 6157.00, 2, '张三', '13800000001', '广东省 深圳市 南山区 科技园南路 88 号 3 栋 501', '尽快发货', '2026-09-30 10:05:00', NULL, NULL, NULL),
(2, 'SO20260930000002', 2, 8499.00, 8499.00, 1, '李四', '13800000002', '浙江省 杭州市 西湖区 文三路 199 号 创业大厦 12F', NULL, NULL, NULL, NULL, NULL),
(3, 'SO20260929000003', 1, 699.00,  699.00,  4, '张三', '13800000001', '广东省 深圳市 南山区 科技园南路 88 号 3 栋 501', NULL, '2026-09-28 09:30:00', '2026-09-28 18:00:00', '2026-09-29 15:20:00', NULL);

INSERT INTO `order_item` (`order_id`, `product_id`, `sku_id`, `product_name`, `sku_specs`, `product_image`, `price`, `quantity`, `subtotal`) VALUES
(1, 1, 1,  'Star X1 Pro 旗舰手机',        '颜色:曜石黑;版本:12+256G', 'https://picsum.photos/seed/p1/800/800', 5999.00, 1, 5999.00),
(1, 9, 13, '三只松鼠 每日坚果 750g',       '规格:750g/30包',           'https://picsum.photos/seed/p9/800/800', 79.00,  2, 158.00),
(2, 4, 7,  'ThinkPad T14p 2026 商务本',   '配置:i7/32G/1T',           'https://picsum.photos/seed/p4/800/800', 8499.00, 1, 8499.00),
(3, 8, 12, '飞利浦电动牙刷 HX9954',       '颜色:粉白',                'https://picsum.photos/seed/p8/800/800', 699.00, 1, 699.00);

-- ============================================================
-- 清理语句（需要重新造数据时先执行下面这段）
-- ============================================================
-- TRUNCATE TABLE order_item;
-- TRUNCATE TABLE orders;
-- TRUNCATE TABLE cart_item;
-- TRUNCATE TABLE shipping_address;
-- TRUNCATE TABLE product_image;
-- TRUNCATE TABLE inventory_log;
-- TRUNCATE TABLE inventory;
-- TRUNCATE TABLE product_sku;
-- TRUNCATE TABLE product;
-- TRUNCATE TABLE product_category;
-- DELETE FROM user WHERE username = 'wangwu';
