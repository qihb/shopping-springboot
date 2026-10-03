#!/usr/bin/env python3
"""
加购未买召回圈人 —— 仿真数据集生成 + 圈人结果验证脚本

用途：本地库（spring_shop）是 demo 规模（3 条购物车），跑不出有意义的圈人数字。
本脚本在**独立 schema** `spring_shop_demo` 里按真实业务特征造一份仿真数据，
再用与 Java 实现**完全相同的聚合 SQL** 跑一遍圈人，输出可直接用于决策的具体数字。

为什么单独建 schema：不污染开发库 spring_shop，随时可 DROP 重建。

数据特征（刻意还原真实场景，让每个筛选规则都有实际作用）：
  - 四类商品原型：爆款热销 / 价格敏感高弃购 / 常规 / 长尾冷门
  - 加购时长三档：<24h（刚加购，应被窗口过滤）/ 24h~7d（应入池）/ >7d（僵尸车，应被过滤）
  - 部分用户无手机号（触达不了，只能标 reachable=0）
  - 少量羊毛账号（购物车条目数超阈值，应被整体剔除）
  - 订单含待付款 / 已取消 / 已退款（不应计入成交，否则弃购率被低估）

用法：
  /Users/qihaibing/.workbuddy-ai/binaries/python/envs/default/bin/python sql/demo/gen_recall_demo.py
"""

import os
import random
import sys
from datetime import datetime, timedelta

import pymysql
from pymysql.constants import CLIENT

MYSQL = dict(host="127.0.0.1", port=3306, user="root",
             password=os.environ.get("MYSQL_PASSWORD", "root"), charset="utf8mb4",
             client_flag=CLIENT.MULTI_STATEMENTS)
DEMO_DB = "spring_shop_demo"
MIGRATION_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "..", "spring-shop-web", "src", "main", "resources", "db", "migration")

# 圈人参数：与 application.yml 的 stats.cart-recall 默认值保持一致
MIN_IDLE_HOURS = 24
MAX_IDLE_DAYS = 7
MIN_QUANTITY = 1
MAX_CART_SIZE = 200
QTY_CAP = 5
MIN_ABANDON_USERS = 3
MIN_ABANDON_RATE = 0.70
TOP_N = 100
PAID_WINDOW_DAYS = 30

RANDOM_SEED = 20261003

# 每个商品的 SKU 数：cart_item 上有 uk_user_sku（每人每 SKU 一条），
# SKU 太少就造不出「购物车条目数超阈值」的羊毛账号
SKUS_PER_PRODUCT = 4

# (商品数, 类型名, 价格区间, 加购未买用户数区间, 近30天成交用户数区间)
ARCHETYPES = [
    (8,  "爆款热销",      (1299, 8999), (400, 900),  (900, 2200)),
    (12, "价格敏感高弃购", (699, 6999),  (300, 700),  (5, 40)),
    (25, "常规商品",      (39, 1299),   (60, 250),   (80, 300)),
    (15, "长尾冷门",      (39, 499),    (0, 2),      (0, 3)),
]

CATEGORY_NAMES = ["手机数码", "电脑办公", "家用电器", "食品生鲜", "服饰鞋包", "运动户外"]
BRAND_WORDS = ["Star", "Aero", "Nova", "Prime", "Lite", "Pro", "Max", "Air", "Zen", "Core"]
GOODS_WORDS = ["旗舰手机", "无线耳机", "智能手表", "商务本", "无线鼠标", "机械键盘",
               "智能电饭煲", "电动牙刷", "每日坚果", "黑麦面包", "保温杯", "跑步鞋",
               "双肩背包", "空气炸锅", "扫地机器人", "蓝牙音箱", "显示器", "移动电源",
               "加湿器", "咖啡机"]


def log(msg):
    print(msg, flush=True)


def read_sql_file(path):
    """读取迁移脚本并去掉行注释（注释里含分号，不能靠 split(';') 切语句）"""
    with open(path, "r", encoding="utf-8") as f:
        return "\n".join(ln for ln in f.read().splitlines() if not ln.strip().startswith("--"))


def apply_migrations(conn):
    """
    复用项目真实迁移脚本建表，保证验证用的 SQL 跑在与线上一致的结构上。
    用 MySQL 多语句模式整段执行：迁移脚本的 COMMENT 里含分号（如「颜色:黑;尺寸:L」），
    自己按分号切会把语句切断。
    """
    for name in ["V1__init_user_schema.sql", "V2__init_product_schema.sql",
                 "V3__init_order_schema.sql", "V6__user_miniapp.sql",
                 "V7__cart_recall.sql"]:
        cur = conn.cursor()
        cur.execute(read_sql_file(os.path.join(MIGRATION_DIR, name)))
        while cur.nextset():
            pass
        conn.commit()
        log(f"  已应用迁移 {name}")


def build_users(conn, rng):
    """用户：约 18% 无手机号（phone 可空，直接决定触达覆盖率）"""
    rows = []
    for uid in range(1, 6001):
        has_phone = rng.random() > 0.18
        has_openid = rng.random() < 0.45
        rows.append((uid, f"demo_u{uid}", "x", f"用户{uid}",
                     f"138{rng.randint(10000000, 99999999)}" if has_phone else None,
                     f"openid_{uid:06d}" if has_openid else None, 1, 0, 0))
    cur = conn.cursor()
    cur.executemany("INSERT INTO `user` (id, username, password, nickname, phone, openid, status, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", rows)
    conn.commit()
    return rows


def build_products(conn, rng):
    """
    商品与 SKU：按原型分配加购未买规模与成交规模，价格决定券面额量级。
    每个商品造多个 SKU —— 真实电商 SPU/SKU 是一对多，且 cart_item 上有
    uk_user_sku 唯一键（每人每 SKU 仅一条），SKU 太少就造不出「购物车条目数超阈值」的羊毛账号。
    """
    products, skus, plan = [], [], []
    pid = 0
    for count, archetype, price_range, abandon_range, paid_range in ARCHETYPES:
        for _ in range(count):
            pid += 1
            price = round(rng.uniform(*price_range), 2)
            name = f"{rng.choice(BRAND_WORDS)} {rng.choice(GOODS_WORDS)}"
            category_id = rng.randint(1, len(CATEGORY_NAMES))
            products.append((pid, category_id, name, f"{archetype}｜仿真数据", None, 0, 1, 0, 0))

            sku_ids = []
            for k in range(SKUS_PER_PRODUCT):
                sku_id = (pid - 1) * SKUS_PER_PRODUCT + k + 1
                sku_price = round(price * (1 + k * 0.08), 2)
                skus.append((sku_id, pid, f"SKU-DEMO-{sku_id:05d}", f"规格:版本{k + 1}", sku_price,
                             round(sku_price * 1.15, 2), rng.randint(50, 2000), 1, 0, 0))
                sku_ids.append(sku_id)

            plan.append(dict(product_id=pid, name=name, price=price, archetype=archetype,
                             sku_ids=sku_ids,
                             abandon_users=rng.randint(*abandon_range),
                             paid_users=rng.randint(*paid_range)))

    cur = conn.cursor()
    cur.executemany("INSERT INTO product_category (id, parent_id, name, sort, status) VALUES (%s,0,%s,%s,1)",
                    [(i + 1, n, i + 1) for i, n in enumerate(CATEGORY_NAMES)])
    cur.executemany("INSERT INTO product (id, category_id, name, subtitle, main_image, sales, status, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", products)
    cur.executemany("INSERT INTO product_sku (id, product_id, sku_code, specs, price, original_price, stock, status, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", skus)
    conn.commit()
    return plan


def idle_hours(rng):
    """加购时长分布：25% 刚加购（<24h，应被窗口过滤）、50% 在窗口内、25% 僵尸车（>7d，应被过滤）"""
    r = rng.random()
    if r < 0.25:
        return rng.uniform(0.2, 23.9)
    if r < 0.75:
        return rng.uniform(24.1, MAX_IDLE_DAYS * 24 - 1)
    return rng.uniform(MAX_IDLE_DAYS * 24 + 1, 180 * 24)


def build_cart_items(conn, rng, plan):
    """购物车：只造「当前仍在车里」的条目 —— 下单成功的条目在真实系统里会被物理删除"""
    rows, now = [], datetime.now()
    pairs = set()
    all_skus = [(sid, item["product_id"]) for item in plan for sid in item["sku_ids"]]

    for item in plan:
        for _ in range(item["abandon_users"]):
            uid = rng.randint(1, 6000)
            sku_id = rng.choice(item["sku_ids"])
            if (uid, sku_id) in pairs:
                continue
            pairs.add((uid, sku_id))
            add_time = now - timedelta(hours=idle_hours(rng))
            quantity = 1 if rng.random() < 0.75 else rng.randint(2, 4)
            rows.append((uid, sku_id, quantity, rng.choice([0, 1, 1]),
                         add_time, add_time, 0, 0))

    # 羊毛账号：购物车条目数远超正常用户，用于验证异常账号剔除
    hoarders = []
    for uid in rng.sample(range(1, 6001), 15):
        hoarders.append(uid)
        for sku_id, _ in rng.sample(all_skus, rng.randint(220, len(all_skus))):
            if (uid, sku_id) in pairs:
                continue
            pairs.add((uid, sku_id))
            add_time = now - timedelta(hours=idle_hours(rng))
            rows.append((uid, sku_id, rng.randint(1, 3), 1, add_time, add_time, 0, 0))

    cur = conn.cursor()
    cur.executemany("INSERT INTO cart_item (user_id, sku_id, quantity, checked, create_time, update_time, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", rows)
    conn.commit()
    return rows, hoarders


def build_orders(conn, rng, plan):
    """
    订单：按成交规模造单。
    状态分布刻意含待付款 / 已取消 / 已退款 —— 它们不算成交，
    若口径写成「不等于已取消」就会把待付款也当成交，弃购率被系统性低估。
    """
    orders, items, order_id, order_seq = [], [], 0, 0
    now = datetime.now()
    status_pool = [2, 2, 2, 3, 3, 4, 1, 5, 6]

    for item in plan:
        for _ in range(item["paid_users"]):
            order_id += 1
            order_seq += 1
            uid = rng.randint(1, 6000)
            status = rng.choice(status_pool)
            quantity = 1 if rng.random() < 0.8 else 2
            amount = round(item["price"] * quantity, 2)
            create_time = now - timedelta(days=rng.uniform(0.5, PAID_WINDOW_DAYS - 1))
            orders.append((order_id, f"SO{order_seq:012d}", uid, amount, amount, status,
                           f"收货人{uid}", f"138{rng.randint(10000000, 99999999)}", "仿真地址",
                           None, create_time, create_time, 0, 0))
            items.append((order_id, item["product_id"], item["sku_ids"][0], item["name"],
                          "规格:默认", None, item["price"], quantity, amount, create_time, create_time, 0, 0))

    cur = conn.cursor()
    cur.executemany("INSERT INTO orders (id, order_no, user_id, total_amount, pay_amount, status, receiver_name, "
                    "receiver_phone, receiver_address, remark, create_time, update_time, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", orders)
    cur.executemany("INSERT INTO order_item (order_id, product_id, sku_id, product_name, sku_specs, product_image, "
                    "price, quantity, subtotal, create_time, update_time, is_deleted, version) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", items)
    conn.commit()
    return orders


SQL_TARGETS = f"""
SELECT c.id AS cart_item_id, c.user_id, c.sku_id, s.product_id, p.name AS product_name,
       p.main_image, s.price AS sku_price, c.quantity, c.quantity * s.price AS cart_amount,
       c.create_time AS add_time, u.phone, u.openid
FROM cart_item c
JOIN product_sku s ON s.id = c.sku_id AND s.is_deleted = 0 AND s.status = 1
JOIN product p     ON p.id = s.product_id AND p.is_deleted = 0 AND p.status = 1
JOIN `user` u      ON u.id = c.user_id AND u.is_deleted = 0 AND u.status = 1
WHERE c.is_deleted = 0
  AND c.quantity >= %(min_quantity)s
  AND c.create_time <= %(idle_before)s
  AND c.create_time >= %(idle_after)s
"""

SQL_PRODUCT_AGG = f"""
SELECT t.product_id, t.product_name,
       COUNT(DISTINCT t.user_id) AS abandon_user_cnt,
       COUNT(*) AS abandon_item_cnt,
       SUM(LEAST(t.quantity, {QTY_CAP}) * t.sku_price) AS abandon_amount,
       COALESCE(paid.paid_user_cnt, 0) AS paid_user_cnt
FROM cart_recall_target t
LEFT JOIN (
    SELECT oi.product_id AS product_id, COUNT(DISTINCT o.user_id) AS paid_user_cnt
    FROM order_item oi
    JOIN orders o ON o.id = oi.order_id
    WHERE oi.is_deleted = 0 AND o.is_deleted = 0
      AND o.status IN (2, 3, 4)
      AND o.create_time >= %(paid_from)s
    GROUP BY oi.product_id
) paid ON paid.product_id = t.product_id
WHERE t.stat_date = %(stat_date)s
GROUP BY t.product_id, t.product_name
"""

SQL_SUMMARY = """
SELECT COUNT(*) AS total_items,
       COUNT(DISTINCT user_id) AS total_users,
       COALESCE(SUM(cart_amount), 0) AS total_amount,
       COALESCE(SUM(CASE WHEN reachable = 1 THEN 1 ELSE 0 END), 0) AS reachable_items,
       COUNT(DISTINCT CASE WHEN reachable = 1 THEN user_id END) AS reachable_users,
       COUNT(DISTINCT CASE WHEN user_phone IS NOT NULL AND user_phone <> '' THEN user_id END) AS phone_users,
       COUNT(DISTINCT CASE WHEN user_openid IS NOT NULL AND user_openid <> '' THEN user_id END) AS openid_users
FROM cart_recall_target WHERE stat_date = %(stat_date)s
"""


def run_recall(conn):
    """
    用与 Java 实现完全相同的 SQL 与口径跑圈人：
    候选 → 剔除异常账号 → 写入人群池 → 从池子聚合选品。
    走真实迁移建出来的 cart_recall_target，保证验证的就是线上那套 SQL。
    """
    now = datetime.now()
    stat_date = now.date()
    params = dict(
        min_quantity=MIN_QUANTITY,
        idle_before=now - timedelta(hours=MIN_IDLE_HOURS),
        idle_after=now - timedelta(days=MAX_IDLE_DAYS),
        paid_from=now - timedelta(days=PAID_WINDOW_DAYS),
        stat_date=stat_date,
    )

    cur = conn.cursor(pymysql.cursors.DictCursor)
    cur.execute("SELECT user_id FROM cart_item WHERE is_deleted = 0 "
                "GROUP BY user_id HAVING COUNT(*) > %s", (MAX_CART_SIZE,))
    abnormal = {r["user_id"] for r in cur.fetchall()}

    cur.execute(SQL_TARGETS, params)
    candidates = cur.fetchall()
    targets = [t for t in candidates if t["user_id"] not in abnormal]
    # 漏斗要能逐层相加等于总数，因此每一层的「剔除量」必须在本层入口集合内计算：
    # candidates 已通过时间窗 + 有效性 + 数量校验，在此之内再剔羊毛账号，
    # 才是「羊毛账号」这一层真正砍掉的行数（不是全表里羊毛账号的总条目数，那会重复计数）
    dropped_hoard = len(candidates) - len(targets)

    # 写入人群池（与 Java 一致：补闲置时长、触达标识、建议券面额）
    cur.execute("DELETE FROM cart_recall_target WHERE status = 0")
    pool_rows = []
    for t in targets:
        idle_hours_val = int((now - t["add_time"]).total_seconds() // 3600)
        amount = min(max(float(t["cart_amount"]) * 0.05, 5.0), 200.0)
        pool_rows.append((t["user_id"], t["sku_id"], t["product_id"], t["product_name"],
                          t["main_image"], t["sku_price"], t["quantity"], t["cart_amount"],
                          t["add_time"], idle_hours_val, t["phone"], t["openid"],
                          1 if (t["phone"] or t["openid"]) else 0, round(amount, 2), 0, stat_date))
    cur.executemany("INSERT INTO cart_recall_target (user_id, sku_id, product_id, product_name, main_image, "
                    "sku_price, quantity, cart_amount, add_time, idle_hours, user_phone, user_openid, "
                    "reachable, suggested_amount, status, stat_date) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", pool_rows)
    conn.commit()

    cur.execute(SQL_PRODUCT_AGG, params)
    agg = cur.fetchall()
    cur.execute(SQL_SUMMARY, params)
    summary = cur.fetchone()

    return params, abnormal, targets, agg, summary, len(candidates), dropped_hoard


def rank_products(agg):
    """与 Java 一致的选品规则：过「加购基数 + 弃购率」门槛，再按加购未买金额降序"""
    ranked = []
    for item in agg:
        abandon_users = item["abandon_user_cnt"]
        paid_users = item["paid_user_cnt"]
        base = abandon_users + paid_users
        rate = abandon_users / base if base else 0
        if abandon_users < MIN_ABANDON_USERS or rate < MIN_ABANDON_RATE:
            continue
        item = dict(item, abandon_rate=rate)
        ranked.append(item)
    ranked.sort(key=lambda x: (-float(x["abandon_amount"]), -x["abandon_user_cnt"], x["product_id"]))
    return ranked


def fmt_money(v):
    return f"{v:,.2f}"


def report(conn, params, abnormal, targets, agg, summary, hoarders, cart_rows, order_rows, plan,
           candidate_cnt, dropped_hoard):
    line = "=" * 78
    log("")
    log(line)
    log("加购未买召回圈人 —— 仿真数据集实测结果")
    log(line)
    log("说明：绝对金额取决于仿真的价格分布，请重点看「比例与结构」；")
    log("      接入真实数据后，这些数字会被真实值替换，而口径与 SQL 完全一致。")

    total_cart = len(cart_rows)
    log("")
    log("【一】数据规模（仿真数据集）")
    log(f"  用户数                                {6000:>12,}")
    log(f"  商品数                                {len(plan):>12,}")
    log(f"  SKU 数                                {len(plan) * SKUS_PER_PRODUCT:>12,}")
    log(f"  购物车条目总数                        {total_cart:>12,}")
    log(f"  订单数                                {len(order_rows):>12,}")

    log("")
    log("【二】过滤漏斗：每一层砍掉了什么")
    log("  （逐层相加 = 总数，每层的剔除量都在本层入口集合内计算）")
    idle_fresh = sum(1 for r in cart_rows if r[4] > params["idle_before"])
    idle_zombie = sum(1 for r in cart_rows if r[4] < params["idle_after"])
    idle_ok = total_cart - idle_fresh - idle_zombie
    invalid = idle_ok - candidate_cnt
    log(f"  购物车条目总数                        {total_cart:>10,}")
    log(f"  - 刚加购不足 24 小时（现在发券是骚扰）   {-idle_fresh:>10,}   {idle_fresh / total_cart * 100:>5.1f}%")
    log(f"  - 超过 7 天僵尸购物车（用户早忘了）      {-idle_zombie:>10,}   {idle_zombie / total_cart * 100:>5.1f}%")
    log(f"  = 落在召回窗口内（24 小时 ~ 7 天）       {idle_ok:>10,}   {idle_ok / total_cart * 100:>5.1f}%")
    log(f"  - 商品下架/账号异常/数量不足            {-invalid:>10,}   {invalid / total_cart * 100:>5.1f}%")
    log(f"  = 通过有效性校验                        {candidate_cnt:>10,}   {candidate_cnt / total_cart * 100:>5.1f}%")

    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM cart_item WHERE user_id IN (%s)" %
                ",".join(str(u) for u in hoarders))
    hoard_items_all = cur.fetchone()[0]
    log(f"  - 羊毛账号（购物车 > {MAX_CART_SIZE} 条）整体剔除   {-dropped_hoard:>10,}   {dropped_hoard / total_cart * 100:>5.1f}%")
    log(f"  = 最终入池条目                          {len(targets):>10,}   {len(targets) / total_cart * 100:>5.1f}%")
    log(f"  （注：{len(hoarders)} 个羊毛账号全表共 {hoard_items_all:,} 条，"
        f"其中 {dropped_hoard:,} 条落在窗口内被本层剔除，"
        f"另 {hoard_items_all - dropped_hoard:,} 条已被时间窗先一步砍掉）")

    log("")
    log("【三】待召回人群池（具体数字）")
    users = summary["total_users"]
    reach_users = summary["reachable_users"]
    total_amount = float(summary["total_amount"])
    log(f"  入池条目数                            {summary['total_items']:>10,}")
    log(f"  涉及用户数（去重）                     {users:>10,}")
    log(f"  涉及商品数                            {len({t['product_id'] for t in targets}):>10,}")
    log(f"  加购未买总金额                        {fmt_money(total_amount):>14} 元")
    log(f"  人均加购金额                          {fmt_money(total_amount / max(users, 1)):>14} 元")
    log("")
    log(f"  可触达条目数（有手机号或 openid）        {summary['reachable_items']:>10,}   "
        f"{summary['reachable_items'] / max(summary['total_items'], 1) * 100:>5.1f}%")
    log(f"  可触达用户数                          {reach_users:>10,}   {reach_users / max(users, 1) * 100:>5.1f}%")
    log(f"  有手机号用户数                        {summary['phone_users']:>10,}   "
        f"{summary['phone_users'] / max(users, 1) * 100:>5.1f}%")
    log(f"  有 openid 用户数                      {summary['openid_users']:>10,}   "
        f"{summary['openid_users'] / max(users, 1) * 100:>5.1f}%")
    log(f"  ** 触达不到的用户数                    {users - reach_users:>10,}   "
        f"{(users - reach_users) / max(users, 1) * 100:>5.1f}%")

    log("")
    log("【四】加购时长分布（决定该不该现在发券）")
    now = datetime.now()
    for label, lo, hi in [("24-48 小时", 24, 48), ("2-3 天", 48, 72), ("3-7 天", 72, 168)]:
        n = sum(1 for t in targets if lo <= (now - t["add_time"]).total_seconds() / 3600 < hi)
        log(f"  {label:<14}{n:>10,}   {n / max(len(targets), 1) * 100:>5.1f}%")

    ranked = rank_products(agg)
    log("")
    log("【五】选品结果：值得发券的 TOP 15")
    log(f"  规则：加购未买用户 ≥ {MIN_ABANDON_USERS} 且弃购率 ≥ {MIN_ABANDON_RATE:.0%}，再按加购未买金额降序")
    log(f"  {'排名':<4}{'商品':<20}{'加购未买用户':>12}{'已成交':>8}{'弃购率':>8}{'加购未买金额':>16}")
    log("  " + "-" * 74)
    for i, item in enumerate(ranked[:15], 1):
        log(f"  {i:<4}{item['product_name']:<20}{item['abandon_user_cnt']:>12,}"
            f"{item['paid_user_cnt']:>8,}{item['abandon_rate'] * 100:>7.1f}%"
            f"{fmt_money(float(item['abandon_amount'])):>16}")
    log(f"  （共 {len(ranked)} 个商品通过门槛）")

    log("")
    log("【六】对照：如果按「加购人数」排序（原方案），会选出什么")
    by_volume = sorted(agg, key=lambda x: -x["abandon_user_cnt"])[:5]
    log(f"  {'商品':<20}{'加购未买用户':>12}{'已成交':>8}{'弃购率':>8}")
    log("  " + "-" * 50)
    for item in by_volume:
        base = item["abandon_user_cnt"] + item["paid_user_cnt"]
        rate = item["abandon_user_cnt"] / base if base else 0
        log(f"  {item['product_name']:<20}{item['abandon_user_cnt']:>12,}"
            f"{item['paid_user_cnt']:>8,}{rate * 100:>7.1f}%")
    log("  ↑ 这些是热门商品：本来就好卖，给它打折等于白送利润")

    log("")
    log("【七】建议券面额测算（加购金额 × 5%，收敛到 5 ~ 200 元）")
    amounts = sorted(min(max(float(t["cart_amount"]) * 0.05, 5.0), 200.0) for t in targets)
    full_cost = sum(amounts)
    reachable_cost = sum(min(max(float(t["cart_amount"]) * 0.05, 5.0), 200.0)
                         for t in targets if t["phone"] or t["openid"])
    top10_ids = {i["product_id"] for i in ranked[:10]}
    top10_cost = sum(min(max(float(t["cart_amount"]) * 0.05, 5.0), 200.0)
                     for t in targets if t["product_id"] in top10_ids)
    log(f"  待发券条目数                          {len(amounts):>10,}")
    log(f"  中位数面额                            {fmt_money(amounts[len(amounts) // 2]):>14} 元")
    log(f"  平均面额                              {fmt_money(full_cost / len(amounts)):>14} 元")
    log("")
    log(f"  方案 A 全量发（含触达不到的人）          {fmt_money(full_cost):>14} 元")
    log(f"  方案 B 只发可触达人群                  {fmt_money(reachable_cost):>14} 元")
    log(f"  方案 C 只发弃购率 TOP 10 商品           {fmt_money(top10_cost):>14} 元   ← 成本降到 {top10_cost / full_cost * 100:.1f}%")
    log(f"  券成本 / 加购未买金额                   {full_cost / total_amount * 100:>13.2f}%")

    log("")
    log(line)
    log("结论速览")
    log(line)
    log(f"  1. 圈出 {len(targets):,} 条待召回，覆盖 {users:,} 个用户、{fmt_money(total_amount)} 元加购未买金额")
    log(f"  2. 过滤漏斗砍掉 {(total_cart - len(targets)) / total_cart * 100:.1f}%："
        f"时间窗外 {idle_fresh + idle_zombie:,} + 无效/数量不足 {invalid:,} + 羊毛账号 {dropped_hoard:,}")
    log(f"  3. 触达覆盖率 {reach_users / max(users, 1) * 100:.1f}%，"
        f"{users - reach_users:,} 个用户既无手机号也无 openid —— 发券也送不出去")
    log(f"  4. 通过门槛的商品 {len(ranked)} 个（占总商品数 {len(ranked) / len(plan) * 100:.0f}%），"
        f"只对这批发券成本 {fmt_money(top10_cost)} 元")
    log("")


def main():
    rng = random.Random(RANDOM_SEED)

    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute(f"DROP DATABASE IF EXISTS {DEMO_DB}")
    cur.execute(f"CREATE DATABASE {DEMO_DB} DEFAULT CHARACTER SET utf8mb4")
    conn.commit()
    conn.select_db(DEMO_DB)
    log(f"已重建仿真库 {DEMO_DB}")

    apply_migrations(conn)
    log("  生成用户 ...")
    build_users(conn, rng)
    log("  生成商品与 SKU ...")
    plan = build_products(conn, rng)
    log("  生成购物车（含羊毛账号）...")
    cart_rows, hoarders = build_cart_items(conn, rng, plan)
    log("  生成订单 ...")
    order_rows = build_orders(conn, rng, plan)

    log("  执行圈人 SQL ...")
    (params, abnormal, targets, agg, summary,
     candidate_cnt, dropped_hoard) = run_recall(conn)
    report(conn, params, abnormal, targets, agg, summary, hoarders, cart_rows, order_rows, plan,
           candidate_cnt, dropped_hoard)
    conn.close()


if __name__ == "__main__":
    sys.exit(main())
