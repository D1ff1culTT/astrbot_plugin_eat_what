# -*- coding: utf-8 -*-
"""EatWhat 数据层：SQLite 存储与美食记录业务逻辑。

数据文件位于 AstrBot 插件数据目录（data/astrbot_plugin_eat_what/），
更新/重装插件不丢数据。本模块不依赖 AstrBot，可独立测试。

数据模型：
- 区块树（areas）：任意深度的层级分类，如 广州市/天河区/某大学/某饭堂/某窗口，
  每个节点向上聚合其子树内所有餐厅的评价，实现逐级评分与排序。
- 评价双轨（reviews.mode）：0=堂食，1=外卖，所有排名与统计均可按模式筛选。
"""
from __future__ import annotations

import json
import random
import sqlite3
import threading
import uuid
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import List, Optional
from urllib.parse import quote

MODE_ALL, MODE_DINE_IN, MODE_DELIVERY = -1, 0, 1

MIME_EXT = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif"}


class StoreError(Exception):
    """业务错误，message 可直接展示给用户；code 对应 HTTP 状态码。"""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def clamp_mode(mode) -> int:
    try:
        m = int(mode)
    except (TypeError, ValueError):
        return MODE_ALL
    return m if m in (MODE_ALL, MODE_DINE_IN, MODE_DELIVERY) else MODE_ALL


SCHEMA = """
CREATE TABLE IF NOT EXISTS areas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    parent_id INTEGER REFERENCES areas(id),
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS restaurants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    address TEXT DEFAULT '',
    lat REAL,
    lng REAL,
    area_id INTEGER REFERENCES areas(id),
    tags TEXT NOT NULL DEFAULT '[]',
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS dishes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    restaurant_id INTEGER NOT NULL REFERENCES restaurants(id),
    name TEXT NOT NULL,
    tags TEXT NOT NULL DEFAULT '[]',
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    UNIQUE(restaurant_id, name)
);

CREATE TABLE IF NOT EXISTS reviews (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    restaurant_id INTEGER NOT NULL REFERENCES restaurants(id),
    dish_id INTEGER REFERENCES dishes(id),
    mode INTEGER NOT NULL DEFAULT 0,
    rating INTEGER NOT NULL,
    comment TEXT DEFAULT '',
    images TEXT DEFAULT '[]',
    lat REAL,
    lng REAL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);
"""

SCHEMA_INDEXES = """
CREATE UNIQUE INDEX IF NOT EXISTS idx_restaurants_name ON restaurants(name);
CREATE INDEX IF NOT EXISTS idx_restaurants_area ON restaurants(area_id);
CREATE INDEX IF NOT EXISTS idx_areas_parent ON areas(parent_id);
CREATE INDEX IF NOT EXISTS idx_reviews_rest ON reviews(restaurant_id);
CREATE INDEX IF NOT EXISTS idx_reviews_dish ON reviews(dish_id);
CREATE INDEX IF NOT EXISTS idx_reviews_mode ON reviews(mode);
"""

# 排名查询：餐厅综合分优先取「整体评价」均分，没有整体评价时退回「菜品评价」均分；mode 筛选堂食/外卖
# 餐厅评分 = 其全部评价（整体+菜品）的平均分，每条新评价都会影响分数
_REST_LIST_SQL = """
SELECT r.id, r.name, r.address, r.lat, r.lng, r.area_id, r.tags,
       a.avg_rating AS avg_rating,
       COALESCE(o.cnt, 0) AS overall_reviews,
       COALESCE(d.cnt, 0) AS dish_review_count,
       (SELECT d2.name FROM dishes d2
          JOIN reviews rv2 ON rv2.dish_id = d2.id
         WHERE d2.restaurant_id = r.id AND (:m = -1 OR rv2.mode = :m)
         GROUP BY d2.id ORDER BY AVG(rv2.rating) DESC, COUNT(*) DESC
         LIMIT 1) AS top_dish,
       (SELECT rv3.images FROM reviews rv3
         WHERE rv3.restaurant_id = r.id AND rv3.images != '[]'
         ORDER BY rv3.id DESC LIMIT 1) AS images_sample
FROM restaurants r
JOIN (SELECT restaurant_id, ROUND(AVG(rating), 2) AS avg_rating
        FROM reviews WHERE (:m = -1 OR mode = :m)
        GROUP BY restaurant_id) a
       ON a.restaurant_id = r.id
LEFT JOIN (SELECT restaurant_id, COUNT(*) AS cnt
             FROM reviews WHERE dish_id IS NULL AND (:m = -1 OR mode = :m)
             GROUP BY restaurant_id) o
       ON o.restaurant_id = r.id
LEFT JOIN (SELECT restaurant_id, COUNT(*) AS cnt
             FROM reviews WHERE dish_id IS NOT NULL AND (:m = -1 OR mode = :m)
             GROUP BY restaurant_id) d
       ON d.restaurant_id = r.id
WHERE (:tag = '' OR r.tags LIKE '%' || '"' || :tag || '"' || '%')
  AND (:kw = '' OR r.name LIKE '%' || :kw || '%' OR r.address LIKE '%' || :kw || '%'
       OR r.tags LIKE '%' || :kw || '%'
       OR EXISTS (SELECT 1 FROM dishes dd WHERE dd.restaurant_id = r.id
                  AND (dd.name LIKE '%' || :kw || '%' OR dd.tags LIKE '%' || :kw || '%')))
ORDER BY a.avg_rating DESC, COALESCE(o.cnt, 0) + COALESCE(d.cnt, 0) DESC, r.id ASC
LIMIT :lim
"""

_DISH_RANK_SQL = """
SELECT d.id, d.name AS dish_name, d.restaurant_id, r.name AS restaurant_name,
       d.tags AS dish_tags,
       ROUND(AVG(rv.rating), 2) AS avg_rating, COUNT(*) AS review_count,
       MAX(rv.created_at) AS last_time,
       (SELECT rv2.images FROM reviews rv2 WHERE rv2.dish_id = d.id AND rv2.images != '[]'
        ORDER BY rv2.rating DESC, rv2.id DESC LIMIT 1) AS images_sample
FROM dishes d
JOIN restaurants r ON r.id = d.restaurant_id
JOIN reviews rv ON rv.dish_id = d.id
WHERE (:m = -1 OR rv.mode = :m)
  AND (:tag = '' OR d.tags LIKE '%' || '"' || :tag || '"' || '%')
  AND (:kw = '' OR d.name LIKE '%' || :kw || '%' OR r.name LIKE '%' || :kw || '%'
       OR d.tags LIKE '%' || :kw || '%')
GROUP BY d.id
ORDER BY avg_rating DESC, review_count DESC, d.id ASC
LIMIT :lim
"""

# 餐厅菜品排名：每家餐厅自己的招牌菜（各店第一名再横向比）
_SIGNATURE_SQL = """
SELECT t.restaurant_id, r.name AS restaurant_name, t.dish_name,
       t.avg_rating, t.review_count, t.images_sample
FROM (
    SELECT d.restaurant_id, d.name AS dish_name,
           ROUND(AVG(rv.rating), 2) AS avg_rating, COUNT(*) AS review_count,
           (SELECT rv2.images FROM reviews rv2 WHERE rv2.dish_id = d.id AND rv2.images != '[]'
            ORDER BY rv2.rating DESC, rv2.id DESC LIMIT 1) AS images_sample,
           ROW_NUMBER() OVER (PARTITION BY d.restaurant_id
                              ORDER BY AVG(rv.rating) DESC, COUNT(*) DESC) AS rn
    FROM dishes d
    JOIN reviews rv ON rv.dish_id = d.id
    WHERE (:m = -1 OR rv.mode = :m)
      AND (:tag = '' OR d.tags LIKE '%' || '"' || :tag || '"' || '%')
    GROUP BY d.id
) t
JOIN restaurants r ON r.id = t.restaurant_id
WHERE t.rn = 1
ORDER BY t.avg_rating DESC, t.review_count DESC
LIMIT :lim
"""


def _imgs(raw) -> List[str]:
    if not raw:
        return []
    try:
        v = json.loads(raw)
        return v if isinstance(v, list) else []
    except Exception:
        return []


def _opt_float(v):
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _rating_ok(v) -> bool:
    """百分制：1~100 的整数。"""
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return False
    return 1 <= v <= 100 and float(v).is_integer()


def map_urls(lat, lng, name: str, address: str) -> dict:
    """地图呈现：有坐标给高德标点（app 内优先唤起高德，网页兜底），只有地址则给搜索页。"""
    urls = {}
    if lat is not None and lng is not None:
        urls["amap_uri"] = (f"androidamap://viewMap?sourceApplication=eatwhat"
                            f"&poiname={quote(name)}&lat={lat}&lon={lng}&dev=0")
        urls["amap_web"] = f"https://uri.amap.com/marker?position={lng},{lat}&name={quote(name)}"
    elif address:
        urls["amap_web"] = f"https://www.amap.com/search?query={quote(address)}"
    return urls


class EatWhatStore:
    """线程安全的 SQLite 存储与查询。所有方法同步执行（个人数据量级，耗时微秒级）。"""

    def __init__(self, db_path: Path, images_dir: Path):
        self.db_path = Path(db_path)
        self.images_dir = Path(images_dir)
        # 可重入锁：create_area 等方法会在持锁期间调用其他持锁方法，
        # 普通 Lock 会自死锁并阻塞 AstrBot 事件循环（表现为整个进程假死）
        self._lock = threading.RLock()
        self.images_dir.mkdir(parents=True, exist_ok=True)
        with self._lock, self._conn() as conn:
            conn.executescript(SCHEMA)
            self._migrate(conn)
            conn.executescript(SCHEMA_INDEXES)

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    @staticmethod
    def _migrate(conn: sqlite3.Connection):
        """对旧版本数据库补列、评分制迁移（幂等）。"""
        rest_cols = {r[1] for r in conn.execute("PRAGMA table_info(restaurants)")}
        if "area_id" not in rest_cols:
            conn.execute("ALTER TABLE restaurants ADD COLUMN area_id INTEGER REFERENCES areas(id)")
        if "tags" not in rest_cols:
            conn.execute("ALTER TABLE restaurants ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
        dish_cols = {r[1] for r in conn.execute("PRAGMA table_info(dishes)")}
        if "tags" not in dish_cols:
            conn.execute("ALTER TABLE dishes ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
        rev_cols = {r[1] for r in conn.execute("PRAGMA table_info(reviews)")}
        if "mode" not in rev_cols:
            conn.execute("ALTER TABLE reviews ADD COLUMN mode INTEGER NOT NULL DEFAULT 0")
        # 评分改百分制：旧 1~5 星一次性 ×20（user_version 0 → 1 保证只跑一次）
        if conn.execute("PRAGMA user_version").fetchone()[0] < 1:
            conn.execute("UPDATE reviews SET rating = rating * 20 WHERE rating <= 5")
            conn.execute("PRAGMA user_version = 1")

    def close(self):
        pass  # 每次操作独立连接，无需常驻

    # ---------------------------------------------------------------- #
    # 区块树
    # ---------------------------------------------------------------- #

    @staticmethod
    def _area_maps(conn):
        areas = {row["id"]: dict(row) for row in conn.execute("SELECT id, name, parent_id FROM areas")}
        children_of = defaultdict(list)
        for a in areas.values():
            children_of[a["parent_id"]].append(a)
        return areas, children_of

    @staticmethod
    def _subtree_ids(children_of, root_id):
        out, stack = set(), [root_id]
        while stack:
            n = stack.pop()
            out.add(n)
            stack.extend(c["id"] for c in children_of.get(n, []))
        return out

    @staticmethod
    def _ancestor_ids(areas_map, area_id):
        chain, cur = [], area_id
        while cur is not None and cur in areas_map:
            chain.append(cur)
            cur = areas_map[cur]["parent_id"]
        return chain  # 自下而上

    def _area_stats(self, conn, children_of, root_id: int, mode: int) -> dict:
        """区块子树聚合：评分 = 子树内全部评价（整体+菜品）的平均分，可按堂食/外卖筛选。"""
        ids = sorted(self._subtree_ids(children_of, root_id))
        if not ids:
            return {"avg_rating": None, "overall_reviews": 0, "dish_review_count": 0,
                    "restaurant_count": 0}
        ph = ",".join("?" * len(ids))
        msql = "" if mode == MODE_ALL else " AND rv.mode = %d" % mode
        row = conn.execute(
            f"""SELECT
                (SELECT COUNT(*) FROM restaurants WHERE area_id IN ({ph})) r_cnt,
                (SELECT ROUND(AVG(rv.rating), 2) FROM reviews rv
                  JOIN restaurants r ON r.id = rv.restaurant_id
                  WHERE r.area_id IN ({ph}){msql}) avg_all,
                (SELECT COUNT(*) FROM reviews rv JOIN restaurants r ON r.id = rv.restaurant_id
                  WHERE r.area_id IN ({ph}) AND rv.dish_id IS NULL{msql}) o_cnt,
                (SELECT COUNT(*) FROM reviews rv JOIN restaurants r ON r.id = rv.restaurant_id
                  WHERE r.area_id IN ({ph}) AND rv.dish_id IS NOT NULL{msql}) d_cnt""",
            ids * 4).fetchone()
        return {"avg_rating": row["avg_all"],
                "overall_reviews": row["o_cnt"], "dish_review_count": row["d_cnt"],
                "restaurant_count": row["r_cnt"]}

    def create_area(self, name, parent_id=None) -> dict:
        name = str(name or "").strip()
        if not name:
            raise StoreError(400, "区块名不能为空")
        if parent_id is not None:
            parent_id = int(parent_id)
        with self._lock, self._conn() as conn:
            if parent_id is not None and \
                    not conn.execute("SELECT 1 FROM areas WHERE id = ?", (parent_id,)).fetchone():
                raise StoreError(404, "上级区块不存在")
            row = conn.execute("SELECT id FROM areas WHERE name = ? AND parent_id IS ?",
                               (name, parent_id)).fetchone()
            if row:
                aid = row["id"]
            else:
                aid = conn.execute("INSERT INTO areas(name, parent_id) VALUES (?,?)",
                                   (name, parent_id)).lastrowid
        # 视图组装放在锁外（areas_view 自身会加锁）
        return self.areas_view(aid, MODE_ALL)

    def areas_view(self, parent_id: Optional[int], mode: int) -> dict:
        """某区块的视图：路径、子区块（含聚合评分，按分排序）、子树内餐厅排名。parent_id None = 根。"""
        mode = clamp_mode(mode)
        with self._lock, self._conn() as conn:
            if parent_id is not None and \
                    not conn.execute("SELECT 1 FROM areas WHERE id = ?", (parent_id,)).fetchone():
                raise StoreError(404, "区块不存在")
            areas, children_of = self._area_maps(conn)

            path = []
            if parent_id is not None:
                for aid in reversed(self._ancestor_ids(areas, parent_id)):
                    path.append({"id": aid, "name": areas[aid]["name"]})

            children = []
            for c in children_of.get(parent_id, []):
                item = dict(c)
                item.update(self._area_stats(conn, children_of, c["id"], mode))
                children.append(item)
            children.sort(key=lambda x: (x["avg_rating"] is None,
                                         -(x["avg_rating"] or 0),
                                         -(x["overall_reviews"] + x["dish_review_count"]),
                                         x["name"]))

            restaurants = []
            if parent_id is not None:
                sub = self._subtree_ids(children_of, parent_id)
                name_by_id = {a["id"]: a["name"] for a in areas.values()}
                for r in self._rest_rows(conn, "", 200, mode):
                    if r["id"] and r["area_id"] in sub:
                        r["area_name"] = name_by_id.get(r["area_id"], "")
                        restaurants.append(r)

            out = {"path": path, "children": children, "restaurants": restaurants}
            if parent_id is not None:
                out["area"] = dict(areas[parent_id]) | self._area_stats(conn, children_of, parent_id, mode)
            else:
                out["area"] = None
            return out

    def find_area_by_name(self, name: str) -> Optional[dict]:
        """按名称模糊找区块（供 AI 工具用），返回含路径的节点信息。"""
        name = str(name or "").strip()
        if not name:
            return None
        with self._lock, self._conn() as conn:
            rows = conn.execute("SELECT id FROM areas WHERE name LIKE ? ORDER BY id LIMIT 1",
                                (f"%{name}%",)).fetchall()
            if not rows:
                return None
            aid = rows[0]["id"]
            areas, children_of = self._area_maps(conn)
            path = [areas[x]["name"] for x in reversed(self._ancestor_ids(areas, aid))]
            return {"id": aid, "name": areas[aid]["name"], "path": path} | \
                self._area_stats(conn, children_of, aid, MODE_ALL)

    # ---------------------------------------------------------------- #
    # 记录
    # ---------------------------------------------------------------- #

    def _resolve_area(self, conn, area_id, area_path) -> Optional[int]:
        """优先用 area_id；否则按路径逐级 find-or-create。"""
        if area_id not in (None, "", 0):
            if not conn.execute("SELECT 1 FROM areas WHERE id = ?", (int(area_id),)).fetchone():
                raise StoreError(404, "区块不存在")
            return int(area_id)
        parent, cur = None, None
        for raw in area_path or []:
            name = str(raw or "").strip()
            if not name:
                continue
            row = conn.execute("SELECT id FROM areas WHERE name = ? AND parent_id IS ?",
                               (name, parent)).fetchone()
            cur = row["id"] if row else conn.execute(
                "INSERT INTO areas(name, parent_id) VALUES (?,?)", (name, parent)).lastrowid
            parent = cur
        return cur

    @staticmethod
    def _tags_json(v, what: str) -> Optional[str]:
        """标签列表校验/序列化：非空列表返回 JSON 文本（用于覆盖写入），否则 None（保留原值）。"""
        if v is None:
            return None
        if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
            raise StoreError(400, f"{what} 的 tags 必须是字符串数组")
        cleaned = [x.strip() for x in v if x.strip()]
        return json.dumps(cleaned, ensure_ascii=False) if cleaned else None

    @staticmethod
    def _find_or_create_restaurant(conn, rest: dict, area_id) -> int:
        name = str(rest.get("name") or "").strip()
        tags = EatWhatStore._tags_json(rest.get("tags"), "餐厅")
        row = conn.execute("SELECT id FROM restaurants WHERE name = ?", (name,)).fetchone()
        if row:
            conn.execute(
                "UPDATE restaurants SET address = COALESCE(NULLIF(?, ''), address),"
                " lat = COALESCE(?, lat), lng = COALESCE(?, lng), area_id = COALESCE(?, area_id),"
                " tags = COALESCE(?, tags)"
                " WHERE id = ?",
                (str(rest.get("address") or "").strip(), _opt_float(rest.get("lat")),
                 _opt_float(rest.get("lng")), area_id, tags, row["id"]))
            return row["id"]
        cur = conn.execute(
            "INSERT INTO restaurants(name, address, lat, lng, area_id, tags) VALUES (?,?,?,?,?,?)",
            (name, str(rest.get("address") or "").strip(), _opt_float(rest.get("lat")),
             _opt_float(rest.get("lng")), area_id, tags or "[]"))
        return cur.lastrowid

    @staticmethod
    def _find_or_create_dish(conn, rid: int, name: str, tags=None) -> int:
        name = name.strip()
        tags = EatWhatStore._tags_json(tags, "菜品")
        row = conn.execute("SELECT id FROM dishes WHERE restaurant_id = ? AND name = ?",
                           (rid, name)).fetchone()
        if row:
            if tags:
                conn.execute("UPDATE dishes SET tags = ? WHERE id = ?", (tags, row["id"]))
            return row["id"]
        return conn.execute(
            "INSERT INTO dishes(restaurant_id, name, tags) VALUES (?,?,?)",
            (rid, name, tags or "[]")).lastrowid

    def create_visit(self, payload: dict) -> dict:
        if not isinstance(payload, dict):
            raise StoreError(400, "请求体必须是 JSON 对象")
        rest = payload.get("restaurant")
        if not isinstance(rest, dict):
            raise StoreError(400, "缺少 restaurant 信息")
        name = str(rest.get("name") or "").strip()
        if not name:
            raise StoreError(400, "餐厅名不能为空")

        dishes = payload.get("dishes") or []
        overall = payload.get("overall")
        mode = payload.get("mode", 0)
        if not isinstance(mode, int) or isinstance(mode, bool) or mode not in (0, 1):
            raise StoreError(400, "mode 必须是 0（堂食）或 1（外卖）")
        if not isinstance(dishes, list):
            raise StoreError(400, "dishes 必须是数组")
        if overall is None and not dishes:
            raise StoreError(400, "至少要有一条评价（整体评价或菜品评价）")
        for d in dishes:
            if not isinstance(d, dict):
                raise StoreError(400, "菜品评价格式错误")
            dn = str(d.get("name") or "").strip()
            if not dn:
                raise StoreError(400, "菜品名不能为空")
            if not _rating_ok(d.get("rating")):
                raise StoreError(400, f"菜品「{dn}」评分必须是 1-100 的整数")
            self._tags_json(d.get("tags"), f"菜品「{dn}」")
        if overall is not None:
            if not isinstance(overall, dict) or not _rating_ok(overall.get("rating")):
                raise StoreError(400, "整体评分必须是 1-100 的整数")
        self._tags_json(rest.get("tags"), "餐厅")

        def _image_list(v, what):
            if not v:
                return []
            if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
                raise StoreError(400, f"{what} 的 images 必须是字符串数组")
            return v

        with self._lock, self._conn() as conn:
            area_id = self._resolve_area(conn, rest.get("area_id"), rest.get("area_path"))
            rid = self._find_or_create_restaurant(conn, rest, area_id)
            n = 0
            for d in dishes:
                did = self._find_or_create_dish(conn, rid, str(d.get("name")).strip(),
                                                d.get("tags"))
                conn.execute(
                    "INSERT INTO reviews(restaurant_id, dish_id, mode, rating, comment, images)"
                    " VALUES (?,?,?,?,?,?)",
                    (rid, did, mode, int(d["rating"]), str(d.get("comment") or "").strip(),
                     json.dumps(_image_list(d.get("images"), f"菜品「{d.get('name')}」"),
                                ensure_ascii=False)))
                n += 1
            if overall is not None:
                conn.execute(
                    "INSERT INTO reviews(restaurant_id, dish_id, mode, rating, comment, images)"
                    " VALUES (?,?,?,?,?,?)",
                    (rid, None, mode, int(overall["rating"]),
                     str(overall.get("comment") or "").strip(),
                     json.dumps(_image_list(overall.get("images"), "整体评价"), ensure_ascii=False)))
                n += 1
        return {"ok": True, "restaurant_id": rid, "review_count": n, "mode": mode}

    def save_image(self, data: bytes, content_type: str) -> dict:
        ctype = (content_type or "").split(";")[0].strip().lower()
        if ctype not in MIME_EXT:
            raise StoreError(400, "仅支持 jpg/png/webp/gif 图片")
        if len(data) > 10 * 1024 * 1024:
            raise StoreError(400, "图片超过 10MB")
        sub = datetime.now().strftime("%Y%m")
        rel = f"{sub}/{uuid.uuid4().hex}{MIME_EXT[ctype]}"
        dst = self.images_dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            dst.write_bytes(data)
        return {"url": f"/images/{rel}"}

    # ---------------------------------------------------------------- #
    # 评价修改 / 删除
    # ---------------------------------------------------------------- #

    def update_review(self, rid: int, payload: dict) -> dict:
        """修改评价：rating / comment / mode 传哪个改哪个。"""
        if not isinstance(payload, dict):
            raise StoreError(400, "请求体必须是 JSON 对象")
        rating = payload.get("rating")
        comment = payload.get("comment")
        mode = payload.get("mode")
        if rating is None and comment is None and mode is None:
            raise StoreError(400, "没有需要修改的字段")
        with self._lock, self._conn() as conn:
            if not conn.execute("SELECT 1 FROM reviews WHERE id = ?", (rid,)).fetchone():
                raise StoreError(404, "评价不存在")
            if rating is not None:
                if not _rating_ok(rating):
                    raise StoreError(400, "评分必须是 1-100 的整数")
                conn.execute("UPDATE reviews SET rating = ? WHERE id = ?", (int(rating), rid))
            if comment is not None:
                conn.execute("UPDATE reviews SET comment = ? WHERE id = ?",
                             (str(comment).strip(), rid))
            if mode is not None:
                if mode not in (0, 1):
                    raise StoreError(400, "mode 必须是 0（堂食）或 1（外卖）")
                conn.execute("UPDATE reviews SET mode = ? WHERE id = ?", (mode, rid))
        return {"ok": True, "review_id": rid}

    def delete_review(self, rid: int) -> dict:
        """删除评价，连带删除其图片文件。"""
        with self._lock, self._conn() as conn:
            row = conn.execute("SELECT images FROM reviews WHERE id = ?", (rid,)).fetchone()
            if not row:
                raise StoreError(404, "评价不存在")
            for url in _imgs(row["images"]):
                if not url.startswith("/images/"):
                    continue
                p = (self.images_dir / url[len("/images/"):]).resolve()
                try:  # 防路径穿越：只删 images 目录内的文件
                    p.relative_to(self.images_dir.resolve())
                except ValueError:
                    continue
                p.unlink(missing_ok=True)
            conn.execute("DELETE FROM reviews WHERE id = ?", (rid,))
        return {"ok": True, "review_id": rid}


    # ---------------------------------------------------------------- #
    # 查询与排名
    # ---------------------------------------------------------------- #

    def _rest_rows(self, conn, keyword: str, limit: int, mode: int = MODE_ALL,
                   tag: str = "") -> List[dict]:
        rows = conn.execute(_REST_LIST_SQL,
                            {"kw": (keyword or "").strip(), "lim": max(1, min(limit, 200)),
                             "m": clamp_mode(mode), "tag": (tag or "").strip()}).fetchall()
        out = []
        for x in rows:
            item = dict(x)
            item["images_sample"] = _imgs(item.pop("images_sample"))
            item["top_dish"] = item.get("top_dish") or ""   # NULL 会变成 "null" 显示
            item["address"] = item.get("address") or ""
            item["tags"] = _imgs(item.get("tags"))
            out.append(item)
        return out

    @staticmethod
    def _norm_dish(x) -> dict:
        item = dict(x)
        item["images_sample"] = _imgs(item.pop("images_sample"))
        if "dish_tags" in item:
            item["tags"] = _imgs(item.pop("dish_tags"))
        elif "tags" in item:
            item["tags"] = _imgs(item.get("tags"))
        return item

    def list_restaurants(self, keyword="", limit=50, mode=MODE_ALL, tag="") -> List[dict]:
        with self._lock, self._conn() as conn:
            return self._rest_rows(conn, str(keyword or ""), int(limit),
                                   clamp_mode(mode), str(tag or ""))

    def rank_dishes(self, limit=20, keyword="", mode=MODE_ALL, tag="") -> List[dict]:
        with self._lock, self._conn() as conn:
            rows = conn.execute(_DISH_RANK_SQL,
                                {"kw": str(keyword or ""), "lim": max(1, min(limit, 200)),
                                 "m": clamp_mode(mode), "tag": str(tag or "")}).fetchall()
            return [self._norm_dish(x) for x in rows]

    def rank_restaurant_dishes(self, limit=20, mode=MODE_ALL, tag="") -> List[dict]:
        """餐厅菜品排名：每家餐厅分数最高的招牌菜，再横向排名。"""
        with self._lock, self._conn() as conn:
            rows = conn.execute(_SIGNATURE_SQL,
                                {"lim": max(1, min(limit, 200)), "m": clamp_mode(mode),
                                 "tag": str(tag or "")}).fetchall()
            return [self._norm_dish(x) for x in rows]

    def list_tags(self) -> dict:
        """全部已用标签（餐厅 + 菜品），供筛选。"""
        with self._lock, self._conn() as conn:
            rtags, dtags = set(), set()
            for row in conn.execute("SELECT tags FROM restaurants"):
                rtags.update(_imgs(row["tags"]))
            for row in conn.execute("SELECT tags FROM dishes"):
                dtags.update(_imgs(row["tags"]))
        return {"restaurant": sorted(rtags), "dish": sorted(dtags)}

    def restaurant_detail(self, rid: int) -> dict:
        with self._lock, self._conn() as conn:
            r = conn.execute("SELECT * FROM restaurants WHERE id = ?", (rid,)).fetchone()
            if not r:
                raise StoreError(404, "餐厅不存在")
            detail = self._rest_rows(conn, r["name"], 1)
            agg = detail[0] if detail else {
                "avg_rating": None, "overall_reviews": 0, "dish_review_count": 0,
                "top_dish": None, "images_sample": []}
            dishes = [self._norm_dish(x) for x in conn.execute(
                """SELECT d.id, d.name, d.tags, ROUND(AVG(rv.rating), 2) AS avg_rating,
                          COUNT(*) AS review_count, MAX(rv.created_at) AS last_time,
                          (SELECT rv2.images FROM reviews rv2 WHERE rv2.dish_id = d.id
                           AND rv2.images != '[]' ORDER BY rv2.id DESC LIMIT 1) AS images_sample
                   FROM dishes d JOIN reviews rv ON rv.dish_id = d.id
                   WHERE d.restaurant_id = ? GROUP BY d.id
                   ORDER BY avg_rating DESC, review_count DESC""", (rid,)).fetchall()]
            reviews = [dict(x) | {"images": _imgs(x["images"]),
                                  "dish_name": x["dish_name"] or ""} for x in conn.execute(
                """SELECT rv.id, rv.dish_id, rv.mode, d.name AS dish_name, rv.rating, rv.comment,
                          rv.images, rv.created_at
                   FROM reviews rv LEFT JOIN dishes d ON d.id = rv.dish_id
                   WHERE rv.restaurant_id = ? ORDER BY rv.id DESC LIMIT 30""", (rid,)).fetchall()]

            # 堂食 / 外卖 分轨统计（该模式全部评价的平均分）
            splits = {}
            for m, key in ((MODE_DINE_IN, "dine_in"), (MODE_DELIVERY, "delivery")):
                row = conn.execute(
                    """SELECT ROUND(AVG(rating), 2) AS avg_all, COUNT(*) AS cnt
                       FROM reviews WHERE restaurant_id = :r AND mode = :m""",
                    {"r": rid, "m": m}).fetchone()
                splits[key] = {"avg": row["avg_all"], "count": row["cnt"]}

            area_path, area_name = [], ""
            if r["area_id"]:
                areas, _ = self._area_maps(conn)
                area_path = [areas[aid]["name"] for aid in reversed(self._ancestor_ids(areas, r["area_id"]))]
                area_name = areas.get(r["area_id"], {}).get("name", "")

        out = {"id": r["id"], "name": r["name"], "address": r["address"],
               "lat": r["lat"], "lng": r["lng"], "created_at": r["created_at"],
               "tags": _imgs(r["tags"]),
               "area_id": r["area_id"], "area_name": area_name, "area_path": area_path,
               "dine_in": splits["dine_in"], "delivery": splits["delivery"]}
        out.update(agg)
        out["dishes"], out["reviews"] = dishes, reviews
        out.update(map_urls(r["lat"], r["lng"], r["name"], r["address"]))
        return out

    def random_pick(self, min_rating=60, mode=MODE_ALL) -> dict:
        """今天吃什么：按评分加权随机挑一家（百分制，分越高权重越大），附招牌菜。"""
        try:
            min_rating = float(min_rating)
        except (TypeError, ValueError):
            min_rating = 60
        with self._lock, self._conn() as conn:
            rows = conn.execute(_REST_LIST_SQL,
                                {"kw": "", "lim": 200, "m": clamp_mode(mode),
                                 "tag": ""}).fetchall()
            if not rows:
                raise StoreError(404, "还没有任何记录，先去 app 里记一餐吧")
            good = [x for x in rows if x["avg_rating"] is not None and x["avg_rating"] >= min_rating]
            pool = good or rows
            weights = [max(5.0, x["avg_rating"] - 60.0) for x in pool]
            pick = random.choices(pool, weights=weights, k=1)[0]
            dish = conn.execute(
                """SELECT d.name, ROUND(AVG(rv.rating), 2) AS avg_rating
                   FROM dishes d JOIN reviews rv ON rv.dish_id = d.id
                   WHERE d.restaurant_id = :r AND (:m = -1 OR rv.mode = :m)
                   GROUP BY d.id
                   ORDER BY avg_rating DESC, COUNT(*) DESC LIMIT 1""",
                {"r": pick["id"], "m": clamp_mode(mode)}).fetchone()
        return {"restaurant": dict(pick) | {"images_sample": _imgs(pick["images_sample"])},
                "dish": dict(dish) if dish else None,
                "map": map_urls(pick["lat"], pick["lng"], pick["name"], pick["address"])}

    def health(self) -> dict:
        with self._lock, self._conn() as conn:
            counts = {}
            for t in ("restaurants", "dishes", "reviews", "areas"):
                counts[t] = conn.execute(f"SELECT COUNT(*) c FROM {t}").fetchone()["c"]
        return {"status": "ok", "service": "eatwhat", **counts}

    def export_all(self) -> dict:
        with self._lock, self._conn() as conn:
            return {
                "areas": [dict(x) for x in conn.execute("SELECT * FROM areas")],
                "restaurants": [dict(x) for x in conn.execute("SELECT * FROM restaurants")],
                "dishes": [dict(x) for x in conn.execute("SELECT * FROM dishes")],
                "reviews": [dict(x) for x in conn.execute("SELECT * FROM reviews")],
            }
