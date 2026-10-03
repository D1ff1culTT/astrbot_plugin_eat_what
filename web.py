# -*- coding: utf-8 -*-
"""EatWhat HTTP 服务（aiohttp）：手机 app 的录入/查询端点。

挂在 AstrBot 事件循环上（AppRunner + TCPSite），端点与手机 app 约定完全一致：
/api/health、/api/visits、/api/restaurants、/api/areas、/api/rank/*、
/api/random、/api/upload、/api/geocode、/api/export、/api/reviews/*、/images/*。

重要：store 是同步实现，所有 DB/文件操作必须经 asyncio.to_thread 进入线程池，
绝不能直接在事件循环线程执行，否则会阻塞整个 AstrBot。
本模块不依赖 AstrBot，可独立测试。
"""
from __future__ import annotations

import asyncio
import hmac
from typing import Optional

from aiohttp import ClientSession, ClientTimeout, web

try:  # AstrBot 以包形式加载插件；独立测试时退回绝对导入
    from .store import EatWhatStore, StoreError, clamp_mode
except ImportError:  # noqa: F401
    from store import EatWhatStore, StoreError, clamp_mode


class EatWhatService:
    """包裹 EatWhatStore 的 HTTP 服务。"""

    def __init__(self, store: EatWhatStore, host: str, port: int,
                 api_token: str = "", amap_key: str = ""):
        self.store = store
        self._host = host
        self._port = port
        self._api_token = api_token or ""
        self._amap_key = amap_key or ""
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None

    # ------------------------------------------------------------------ #
    # 生命周期
    # ------------------------------------------------------------------ #

    def build_app(self) -> web.Application:
        app = web.Application(client_max_size=12 * 1024 * 1024)
        app.middlewares.append(self._auth_middleware)

        # 健康检查不鉴权，便于 app「测试连接」
        app.router.add_get("/api/health", self._health)

        app.router.add_post("/api/visits", self._create_visit)
        app.router.add_post("/api/upload", self._upload)
        app.router.add_put("/api/reviews/{rid}", self._update_review)
        app.router.add_delete("/api/reviews/{rid}", self._delete_review)
        app.router.add_post("/api/areas", self._create_area)
        app.router.add_get("/api/areas", self._list_areas)
        app.router.add_get("/api/restaurants", self._list_restaurants)
        app.router.add_get("/api/restaurants/{rid}", self._restaurant_detail)
        app.router.add_get("/api/rank/restaurants", self._rank_restaurants)
        app.router.add_get("/api/rank/dishes", self._rank_dishes)
        app.router.add_get("/api/rank/restaurant_dishes", self._rank_restaurant_dishes)
        app.router.add_get("/api/random", self._random)
        app.router.add_get("/api/tags", self._tags)
        app.router.add_get("/api/geocode", self._geocode)
        app.router.add_get("/api/export", self._export)

        app.router.add_static("/images/", str(self.store.images_dir))
        return app

    async def start(self) -> None:
        app = self.build_app()
        self._runner = web.AppRunner(app, access_log=None)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, self._host, self._port)
        await self._site.start()

    async def stop(self) -> None:
        if self._site:
            await self._site.stop()
        if self._runner:
            await self._runner.cleanup()
        self._site = None
        self._runner = None

    # ------------------------------------------------------------------ #
    # 中间件与工具
    # ------------------------------------------------------------------ #

    @web.middleware
    async def _auth_middleware(self, request: web.Request, handler):
        # /api/health 与 /images/* 不鉴权（图片文件名为随机 uuid）
        if request.path == "/api/health" or request.path.startswith("/images/"):
            return await handler(request)
        if not self._api_token:
            return await handler(request)
        header = request.headers.get("Authorization", "")
        if header.startswith("Bearer ") and hmac.compare_digest(header[7:], self._api_token):
            return await handler(request)
        token = request.headers.get("X-Api-Token", "")
        if hmac.compare_digest(token, self._api_token):
            return await handler(request)
        return web.json_response({"detail": "令牌无效"}, status=401)

    @staticmethod
    def _err(e: StoreError) -> web.Response:
        return web.json_response({"detail": e.message}, status=e.code)

    @staticmethod
    def _query(request: web.Request, name: str, default=""):
        return request.query.get(name, default)

    # ------------------------------------------------------------------ #
    # handlers（store 同步调用一律经 to_thread，避免阻塞事件循环）
    # ------------------------------------------------------------------ #

    async def _health(self, request: web.Request) -> web.Response:
        return web.json_response(await asyncio.to_thread(self.store.health))

    async def _create_visit(self, request: web.Request) -> web.Response:
        try:
            payload = await request.json()
            result = await asyncio.to_thread(self.store.create_visit, payload)
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)
        except Exception:
            return web.json_response({"detail": "请求体不是有效 JSON"}, status=400)

    async def _upload(self, request: web.Request) -> web.Response:
        try:
            post = await request.post()
            field = post.get("file")
            if field is None:
                return web.json_response({"detail": "缺少 file 字段"}, status=400)
            data = await asyncio.to_thread(field.file.read)
            result = await asyncio.to_thread(
                self.store.save_image, data, field.content_type or "")
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)

    async def _update_review(self, request: web.Request) -> web.Response:
        try:
            payload = await request.json()
            result = await asyncio.to_thread(
                self.store.update_review, int(request.match_info["rid"]), payload)
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)
        except (TypeError, ValueError):
            return web.json_response({"detail": "评价 id 无效"}, status=400)
        except Exception:
            return web.json_response({"detail": "请求体不是有效 JSON"}, status=400)

    async def _delete_review(self, request: web.Request) -> web.Response:
        try:
            result = await asyncio.to_thread(
                self.store.delete_review, int(request.match_info["rid"]))
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)
        except (TypeError, ValueError):
            return web.json_response({"detail": "评价 id 无效"}, status=400)

    async def _create_area(self, request: web.Request) -> web.Response:
        try:
            payload = await request.json()
            result = await asyncio.to_thread(
                self.store.create_area, payload.get("name"), payload.get("parent_id"))
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)
        except Exception:
            return web.json_response({"detail": "请求体不是有效 JSON"}, status=400)

    async def _list_areas(self, request: web.Request) -> web.Response:
        try:
            parent = self._query(request, "parent_id", "")
            parent_id = int(parent) if parent not in ("", None) else None
            mode = clamp_mode(self._query(request, "mode", -1))
            return web.json_response(
                await asyncio.to_thread(self.store.areas_view, parent_id, mode))
        except StoreError as e:
            return self._err(e)

    async def _list_restaurants(self, request: web.Request) -> web.Response:
        try:
            rows = await asyncio.to_thread(
                self.store.list_restaurants,
                self._query(request, "keyword"),
                int(self._query(request, "limit", 50)),
                clamp_mode(self._query(request, "mode", -1)),
                self._query(request, "tag"))
            return web.json_response(rows)
        except (TypeError, ValueError):
            return web.json_response({"detail": "limit 参数无效"}, status=400)

    async def _restaurant_detail(self, request: web.Request) -> web.Response:
        try:
            detail = await asyncio.to_thread(
                self.store.restaurant_detail, int(request.match_info["rid"]))
            return web.json_response(detail)
        except StoreError as e:
            return self._err(e)
        except (TypeError, ValueError):
            return web.json_response({"detail": "餐厅 id 无效"}, status=400)

    async def _rank_restaurants(self, request: web.Request) -> web.Response:
        try:
            rows = await asyncio.to_thread(
                self.store.list_restaurants, "",
                int(self._query(request, "limit", 20)),
                clamp_mode(self._query(request, "mode", -1)),
                self._query(request, "tag"))
            return web.json_response(rows)
        except (TypeError, ValueError):
            return web.json_response({"detail": "limit 参数无效"}, status=400)

    async def _rank_dishes(self, request: web.Request) -> web.Response:
        try:
            rows = await asyncio.to_thread(
                self.store.rank_dishes,
                int(self._query(request, "limit", 20)),
                self._query(request, "keyword"),
                clamp_mode(self._query(request, "mode", -1)),
                self._query(request, "tag"))
            return web.json_response(rows)
        except (TypeError, ValueError):
            return web.json_response({"detail": "limit 参数无效"}, status=400)

    async def _rank_restaurant_dishes(self, request: web.Request) -> web.Response:
        try:
            rows = await asyncio.to_thread(
                self.store.rank_restaurant_dishes,
                int(self._query(request, "limit", 20)),
                clamp_mode(self._query(request, "mode", -1)),
                self._query(request, "tag"))
            return web.json_response(rows)
        except (TypeError, ValueError):
            return web.json_response({"detail": "limit 参数无效"}, status=400)

    async def _random(self, request: web.Request) -> web.Response:
        try:
            result = await asyncio.to_thread(
                self.store.random_pick,
                self._query(request, "min_rating", 60),
                clamp_mode(self._query(request, "mode", -1)))
            return web.json_response(result)
        except StoreError as e:
            return self._err(e)

    async def _tags(self, request: web.Request) -> web.Response:
        return web.json_response(await asyncio.to_thread(self.store.list_tags))

    async def _geocode(self, request: web.Request) -> web.Response:
        """坐标 -> 地址文本。优先高德（配置 amap_key），否则 OSM Nominatim；失败返回 null。"""
        try:
            lat, lng = float(self._query(request, "lat")), float(self._query(request, "lng"))
        except (TypeError, ValueError):
            return web.json_response({"detail": "lat/lng 参数无效"}, status=400)
        address = None
        try:
            async with ClientSession(timeout=ClientTimeout(total=6)) as session:
                if self._amap_key:
                    async with session.get(
                            "https://restapi.amap.com/v3/geocode/regeo",
                            params={"key": self._amap_key, "location": f"{lng},{lat}"},
                            headers={"User-Agent": "eatwhat/1.0"}) as resp:
                        data = await resp.json(content_type=None)
                    address = ((data.get("regeocode") or {}).get("formatted_address")) or None
                else:
                    async with session.get(
                            "https://nominatim.openstreetmap.org/reverse",
                            params={"lat": lat, "lon": lng, "format": "json", "zoom": 18,
                                    "accept-language": "zh-CN"},
                            headers={"User-Agent": "eatwhat/1.0 (personal food log)"}) as resp:
                        data = await resp.json(content_type=None)
                    address = data.get("display_name") or None
        except Exception:
            address = None
        return web.json_response({"lat": lat, "lng": lng, "address": address})

    async def _export(self, request: web.Request) -> web.Response:
        return web.json_response(await asyncio.to_thread(self.store.export_all))
