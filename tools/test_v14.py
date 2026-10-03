# -*- coding: utf-8 -*-
"""v1.4 回归测试：区块聚合动态变化、标签筛选、百分制、null 修复。"""
import json
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8769"
HDR = {"Authorization": "Bearer test123", "Content-Type": "application/json"}
ok_count, fail = 0, []


def call(method, path, payload=None, auth=True):
    if "?" in path:
        path, qs = path.split("?", 1)
        path = path + "?" + urllib.parse.quote(qs, safe="=&")
    req = urllib.request.Request(BASE + path, method=method)
    if auth:
        req.add_header("Authorization", "Bearer test123")
    data = None
    if payload is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    with urllib.request.urlopen(req, data=data, timeout=8) as r:
        return json.loads(r.read().decode("utf-8"))


def check(name, cond, extra=""):
    global ok_count
    if cond:
        ok_count += 1
        print("PASS", name, extra)
    else:
        fail.append(name)
        print("FAIL", name, extra)


# 1. 区块
r = call("POST", "/api/areas", {"name": "广州市"})
gz = r["area"]["id"]
call("POST", "/api/areas", {"name": "天河区", "parent_id": gz})
view = call("GET", f"/api/areas?parent_id={gz}")
th = view["children"][0]["id"]

# 2. 商家1：20 分 → 区块聚合 20
call("POST", "/api/visits", {"restaurant": {"name": "商家一", "area_id": th},
                             "overall": {"rating": 20}, "mode": 0})
view = call("GET", f"/api/areas?parent_id={gz}")
check("区块聚合-单店20分", view["children"][0]["avg_rating"] == 20,
      f"avg={view['children'][0]['avg_rating']}")

# 3. 商家2：100 分 → 区块聚合 (20+100)/2 = 60（用户报告的 bug 场景）
call("POST", "/api/visits", {"restaurant": {"name": "商家二", "area_id": th},
                             "dishes": [{"name": "招牌菜A", "rating": 100, "tags": ["辣", "招牌"]}],
                             "overall": {"rating": 100}, "mode": 0})
view = call("GET", f"/api/areas?parent_id={gz}")
check("区块聚合-两店后60分", view["children"][0]["avg_rating"] == 60,
      f"avg={view['children'][0]['avg_rating']}")

# 4. 商家1 换成堂食3分 → (30+100)/2=65；商家2 外卖100
call("POST", "/api/visits", {"restaurant": {"name": "商家一"},
                             "overall": {"rating": 30}, "mode": 0})
view = call("GET", f"/api/areas?parent_id={gz}")
check("区块聚合-动态更新50", view["children"][0]["avg_rating"] == 50,
      f"avg={view['children'][0]['avg_rating']}")

# 5. 标签：带标签商家 + 菜品标签
call("POST", "/api/visits", {"restaurant": {"name": "川菜馆", "area_id": th, "tags": ["川菜", "老字号"]},
                             "dishes": [{"name": "麻婆豆腐", "rating": 88, "tags": ["辣"]}],
                             "overall": {"rating": 90}, "mode": 0})
tags = call("GET", "/api/tags")
check("标签汇总", "川菜" in tags["restaurant"] and "辣" in tags["dish"] and "老字号" in tags["restaurant"],
      json.dumps(tags, ensure_ascii=False))

# 6. 按标签筛选餐厅榜
ranked = call("GET", "/api/rank/restaurants?tag=川菜")
check("餐厅按标签筛选", len(ranked) == 1 and ranked[0]["name"] == "川菜馆",
      [x["name"] for x in ranked])
ranked = call("GET", "/api/rank/dishes?tag=辣")
check("菜品按标签筛选", len(ranked) == 2, [x["dish_name"] for x in ranked])  # 招牌菜A(辣)+麻婆豆腐(辣)
ranked = call("GET", "/api/rank/dishes?tag=招牌")
check("菜品按招牌标签", len(ranked) == 1 and ranked[0]["dish_name"] == "招牌菜A",
      [x["dish_name"] for x in ranked])

# 7. 搜索命中标签
found = call("GET", "/api/restaurants?keyword=老字号")
check("关键词命中标签", any(x["name"] == "川菜馆" for x in found))

# 8. 详情：tags 与 dish_name 无 null
d = call("GET", "/api/restaurants/3")
check("详情餐厅标签", d["tags"] == ["川菜", "老字号"], d["tags"])
check("详情菜品标签", d["dishes"][0]["tags"] == ["辣"], d["dishes"][0]["tags"])

# 9. 无菜品记录餐厅 top_dish 不为 "null"
d = call("GET", "/api/restaurants/1")
check("top_dish 空串(非null)", d["top_dish"] == "", repr(d["top_dish"]))
rv_overall = [r for r in d["reviews"] if r["dish_id"] is None]
check("整体评价 dish_name 空串", all(r["dish_name"] == "" for r in rv_overall),
      [r["dish_name"] for r in rv_overall])

# 10. 百分制校验：5=5分合法，101 越界拒绝
call("POST", "/api/visits", {"restaurant": {"name": "X"}, "overall": {"rating": 5}})
try:
    call("POST", "/api/visits", {"restaurant": {"name": "X2"}, "overall": {"rating": 101}})
    check("百分制校验拒绝101", False)
except urllib.error.HTTPError as e:
    check("百分制校验拒绝101", e.code == 400)

print("=== PASS %d, FAIL %d %s ===" % (ok_count, len(fail), fail if fail else ""))
