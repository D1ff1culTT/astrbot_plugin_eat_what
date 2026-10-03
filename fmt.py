# -*- coding: utf-8 -*-
"""EatWhat 文本渲染：LLM 工具与指令共用的输出格式化。

全部为纯函数（store 结果 -> 文本），不依赖 AstrBot，可独立测试。
约定 mode："全部"/"堂食"/"外卖" -> -1/0/1。
"""
from __future__ import annotations

try:  # AstrBot 以包形式加载插件；独立测试时退回绝对导入
    from .store import StoreError
except ImportError:  # noqa: F401
    from store import StoreError

_MODE_MAP = {"全部": -1, "堂食": 0, "外卖": 1}


def mode_value(mode: str) -> int:
    return _MODE_MAP.get(str(mode or "").strip(), -1)


def mode_label(m: int) -> str:
    return {0: "堂食", 1: "外卖"}.get(m, "")


def stars(avg) -> str:
    """百分制：80 分显示 ★★★★ 80 分。"""
    try:
        avg = float(avg)
    except (TypeError, ValueError):
        return "暂无评分"
    r = max(0, min(5, int(round(avg / 20.0))))
    return "★" * r + "☆" * (5 - r) + f" {avg:g} 分"


def _fmt_restaurant(r: dict, idx=None) -> str:
    head = f"{idx}. " if idx else ""
    line = f"{head}{r['name']}  {stars(r.get('avg_rating'))}"
    parts = []
    o, d = r.get("overall_reviews", 0), r.get("dish_review_count", 0)
    if o or d:
        parts.append(f"整体{o} 菜品{d}")
    if r.get("address"):
        parts.append(r["address"])
    top = r.get("top_dish") or ""
    if top:
        parts.append(f"招牌:{top}")
    if parts:
        line += " ｜ " + " ｜ ".join(parts)
    return line


def render_restaurant_ranking(store, limit: str, mode: str) -> str:
    rows = store.list_restaurants(limit=max(1, min(int(limit or 10), 20)), mode=mode_value(mode))
    if not rows:
        return "美食记录里还没有餐厅，让用户先在手机 app 里记一餐。"
    label = mode_label(mode_value(mode))
    lines = [f"餐厅评分排名（{label or '全部'}）："]
    lines += [_fmt_restaurant(r, i + 1) for i, r in enumerate(rows)]
    return "\n".join(lines)


def render_dish_ranking(store, limit: str, mode: str) -> str:
    rows = store.rank_dishes(limit=max(1, min(int(limit or 10), 20)), mode=mode_value(mode))
    if not rows:
        return "还没有菜品评价记录。"
    label = mode_label(mode_value(mode))
    lines = [f"菜品评分排名（{label or '全部'}）："]
    lines += [f"{i + 1}. {x['dish_name']}（{x['restaurant_name']}）  {stars(x['avg_rating'])}"
              f" · {x['review_count']} 次" for i, x in enumerate(rows)]
    return "\n".join(lines)


def render_signature(store, limit: str, mode: str) -> str:
    rows = store.rank_restaurant_dishes(limit=max(1, min(int(limit or 10), 20)),
                                        mode=mode_value(mode))
    if not rows:
        return "还没有菜品评价记录。"
    label = mode_label(mode_value(mode))
    lines = [f"各店招牌菜（{label or '全部'}）："]
    lines += [f"{i + 1}. {x['restaurant_name']} → {x['dish_name']}  {stars(x['avg_rating'])}"
              f" · {x['review_count']} 次" for i, x in enumerate(rows)]
    return "\n".join(lines)


def render_search(store, keyword: str, mode: str) -> str:
    rows = store.list_restaurants(keyword=str(keyword or "").strip(), limit=8,
                                  mode=mode_value(mode))
    if not rows:
        return f"没找到与「{keyword}」相关的餐厅记录。"
    lines = [f"搜索「{keyword}」结果："]
    lines += [_fmt_restaurant(r, i + 1) for i, r in enumerate(rows)]
    return "\n".join(lines)


def render_detail(store, restaurant_name: str) -> str:
    name = str(restaurant_name or "").strip()
    rows = store.list_restaurants(keyword=name, limit=5)
    if not rows:
        return f"没找到叫「{restaurant_name}」的餐厅记录。"
    if len(rows) > 1 and rows[0]["name"] != name:
        cands = "、".join(r["name"] for r in rows)
        return f"「{restaurant_name}」匹配到多家店：{cands}。请确认店名后再查。"
    d = store.restaurant_detail(rows[0]["id"])
    lines = [f"【{d['name']}】  {stars(d.get('avg_rating'))}"
             f"（整体{d['overall_reviews']} · 菜品{d['dish_review_count']}）"]
    di, de = d.get("dine_in") or {}, d.get("delivery") or {}
    lines.append(f"堂食：{stars(di.get('avg')) if di.get('avg') else '暂无'}"
                 f"（{di.get('count', 0)} 次）｜外卖："
                 f"{stars(de.get('avg')) if de.get('avg') else '暂无'}（{de.get('count', 0)} 次）")
    if d.get("area_path"):
        lines.append("区块：" + " / ".join(d["area_path"]))
    lines.append("地址：" + (d.get("address") or "未记录"))
    if d.get("dishes"):
        lines.append("菜品排名：")
        lines += [f"  {i + 1}. {x['name']}  {stars(x['avg_rating'])} · {x['review_count']} 次"
                  for i, x in enumerate(d["dishes"][:8])]
    recent = [r for r in d.get("reviews", []) if r.get("comment")][:5]
    if recent:
        lines.append("最近评价：")
        for r in recent:
            tag = "外卖" if r.get("mode") == 1 else "堂食"
            dish = f"·{r['dish_name']}" if r.get("dish_name") else ""
            lines.append(f"  [{tag}{dish} {stars(r['rating'])}] {r['comment']}")
    return "\n".join(lines)


def render_area_query(store, area_name: str) -> str:
    node = store.find_area_by_name(str(area_name or "").strip())
    if not node:
        return f"没有找到叫「{area_name}」的区块。"
    view = store.areas_view(node["id"], -1)
    lines = ["区块：" + " / ".join(node["path"]) + f"  {stars(node.get('avg_rating'))}"
             f"（餐厅{node.get('restaurant_count', 0)}）"]
    children = view.get("children", [])
    if children:
        lines.append("子区块排名：")
        lines += [f"  {i + 1}. {c['name']}  {stars(c.get('avg_rating'))}"
                  f" · 餐厅{c.get('restaurant_count', 0)}"
                  for i, c in enumerate(children[:10])]
    rests = view.get("restaurants", [])
    if rests:
        lines.append("区块内餐厅排名：")
        lines += ["  " + _fmt_restaurant(r, i + 1) for i, r in enumerate(rests[:10])]
    if not children and not rests:
        lines.append("（该区块下暂无内容）")
    return "\n".join(lines)


def render_recommend(store, mode: str) -> str:
    r = store.random_pick(60, mode_value(mode))
    rest, dish = r["restaurant"], r.get("dish")
    lines = [f"推荐：{rest['name']}  {stars(rest.get('avg_rating'))}"]
    if rest.get("address"):
        lines.append("地址：" + rest["address"])
    if rest.get("area_path"):
        lines.append("区块：" + " / ".join(rest["area_path"]))
    if dish:
        lines.append(f"推荐菜：{dish['name']}（{stars(dish['avg_rating'])}）")
    return "\n".join(lines)


def safe(render_fn, store, *args) -> str:
    """统一兜底：业务错误转友好文案，意外异常也不断开。"""
    try:
        return render_fn(store, *args)
    except StoreError as e:
        return e.message
    except Exception as e:  # noqa: BLE001
        return f"查询失败：{e}"
