# -*- coding: utf-8 -*-
"""astrbot_plugin_eat_what —— 吃了什么：个人美食记录 + AI 查询插件。

功能：
  1. 内置 aiohttp HTTP 服务：手机 app「吃了什么」在此录入餐厅/菜品/评价/照片，
     数据存于 AstrBot 数据目录 data/astrbot_plugin_eat_what/（SQLite + 图片文件）。
  2. LLM 工具：餐厅/菜品/招牌菜排名（可分堂食、外卖）、找店、看店详情、
     区块逐级查询（如 广州市/天河区/某大学/某饭堂）、加权随机推荐。
  3. 聊天指令：/吃什么 /餐厅排名 /菜品排名 /招牌菜 /美食帮助。

注意：不要在本文件加 from __future__ import annotations——AstrBot 校验 LLM 工具
参数时要求注解是真实类型（str/int/bool），future import 会把注解变成字符串。
"""
from pathlib import Path

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, StarTools, register

from . import fmt
from .store import EatWhatStore, StoreError
from .web import EatWhatService

PLUGIN_NAME = "astrbot_plugin_eat_what"
DEFAULT_PORT = 8765


@register(PLUGIN_NAME, "lihaotian", "吃了什么：手机记录美食（餐厅/菜品/评分/照片/堂食外卖/区块分类），AI 可随时查询排名与推荐。", "1.0.0")
class EatWhatPlugin(Star):
    def __init__(self, context: Context, config) -> None:
        super().__init__(context)
        self.config = config
        self.data_dir: Path = StarTools.get_data_dir(PLUGIN_NAME)
        self.store = EatWhatStore(
            db_path=self.data_dir / "eat_what.db",
            images_dir=self.data_dir / "images")
        self.service = None  # EatWhatService，initialize 时创建

    # ------------------------------------------------------------------ #
    # 生命周期：启动/停止 HTTP 服务
    # ------------------------------------------------------------------ #

    async def initialize(self) -> None:
        host = str(self.config.get("http_host", "0.0.0.0"))
        port = int(self.config.get("http_port", DEFAULT_PORT))
        token = str(self.config.get("api_token", "") or "")
        amap_key = str(self.config.get("amap_key", "") or "")
        try:
            self.service = EatWhatService(self.store, host, port, token, amap_key)
            await self.service.start()
            logger.info(f"[EatWhat] 手机 app 录入端已启动: http://{host}:{port}/api/health "
                        f"(token {'已启用' if token else '未设置'})")
        except Exception as e:  # noqa: BLE001
            logger.error(f"[EatWhat] HTTP 服务启动失败: {e}")

    async def terminate(self) -> None:
        if self.service:
            await self.service.stop()
        self.store.close()

    # ------------------------------------------------------------------ #
    # LLM 工具
    # ------------------------------------------------------------------ #

    @filter.llm_tool(name="restaurant_ranking")
    async def restaurant_ranking(self, event: AstrMessageEvent, limit: str = "10", mode: str = "全部"):
        """查询美食记录中的餐厅评分排名（综合分从高到低）。

        Args:
            limit(string): 返回数量，默认 10
            mode(string): 用餐方式筛选：全部/堂食/外卖，默认全部
        """
        return fmt.safe(fmt.render_restaurant_ranking, self.store, limit, mode)

    @filter.llm_tool(name="dish_ranking")
    async def dish_ranking(self, event: AstrMessageEvent, limit: str = "10", mode: str = "全部"):
        """查询美食记录中的菜品评分排名（所有餐厅横向比较，从高到低）。

        Args:
            limit(string): 返回数量，默认 10
            mode(string): 用餐方式筛选：全部/堂食/外卖，默认全部
        """
        return fmt.safe(fmt.render_dish_ranking, self.store, limit, mode)

    @filter.llm_tool(name="signature_dishes")
    async def signature_dishes(self, event: AstrMessageEvent, limit: str = "10", mode: str = "全部"):
        """查询每家餐厅自己的招牌菜（餐厅菜品排名：各店第一名再横向比较）。

        Args:
            limit(string): 返回数量，默认 10
            mode(string): 用餐方式筛选：全部/堂食/外卖，默认全部
        """
        return fmt.safe(fmt.render_signature, self.store, limit, mode)

    @filter.llm_tool(name="search_restaurant")
    async def search_restaurant(self, event: AstrMessageEvent, keyword: str, mode: str = "全部"):
        """按关键词搜索吃过的餐厅（店名、地址、菜品名均可匹配），返回评分与招牌菜。

        Args:
            keyword(string): 关键词，如 店名、菜名（如 麻婆豆腐）、地址片段
            mode(string): 用餐方式筛选：全部/堂食/外卖，默认全部
        """
        return fmt.safe(fmt.render_search, self.store, keyword, mode)

    @filter.llm_tool(name="restaurant_detail")
    async def restaurant_detail(self, event: AstrMessageEvent, restaurant_name: str):
        """查询某家餐厅的详情：堂食/外卖分轨评分、所在区块路径、菜品排名、最近评价。

        Args:
            restaurant_name(string): 餐厅名，支持部分匹配，取最相关的一家
        """
        return fmt.safe(fmt.render_detail, self.store, restaurant_name)

    @filter.llm_tool(name="area_query")
    async def area_query(self, event: AstrMessageEvent, area_name: str):
        """按区块逐级查询美食记录（如 广州市/天河区/某大学/某饭堂/某窗口），返回该区块的聚合评分、子区块排名与区块内餐厅排名。

        Args:
            area_name(string): 区块名称，支持部分匹配，如 天河区、某饭堂
        """
        return fmt.safe(fmt.render_area_query, self.store, area_name)

    @filter.llm_tool(name="value_ranking")
    async def value_ranking(self, event: AstrMessageEvent, scope: str = "菜品",
                            mode: str = "全部", tag: str = ""):
        """查询性价比排行（性价比 = 评分 ÷ 实付价格 × 10，数值越大越划算）。
        Args:
            scope(string): "菜品" 或 "商家"，默认菜品
            mode(string): "全部"/"堂食"/"外卖"，默认全部
            tag(string): 按标签筛选，可留空
        """
        return fmt.safe(fmt.render_value_ranking, self.store, scope, mode, tag)

    @filter.llm_tool(name="recommend_food")
    async def recommend_food(self, event: AstrMessageEvent, mode: str = "全部"):
        """随机推荐一家吃过的店（评分越高越容易被推荐）+ 招牌菜，用于「今天吃什么」。

        Args:
            mode(string): 用餐方式筛选：全部/堂食/外卖，默认全部
        """
        return fmt.safe(fmt.render_recommend, self.store, mode)

    # ------------------------------------------------------------------ #
    # 指令
    # ------------------------------------------------------------------ #

    def _arg_mode(self, event: AstrMessageEvent) -> int:
        text = str(getattr(event, "message_str", "") or "")
        for kw in ("外卖", "堂食"):
            if kw in text:
                return fmt.mode_value(kw)
        return -1

    @filter.command("吃什么")
    async def cmd_eat(self, event: AstrMessageEvent):
        """随机推荐一家吃过的店（可带参数：堂食/外卖）。"""
        yield event.plain_result(
            "🎲 " + fmt.safe(fmt.render_recommend, self.store, self._arg_label(event)))

    @filter.command("餐厅排名")
    async def cmd_rest_rank(self, event: AstrMessageEvent):
        """餐厅评分排名（可带参数：堂食/外卖）。"""
        yield event.plain_result(
            "🏆 " + fmt.safe(fmt.render_restaurant_ranking, self.store, "15", self._arg_label(event)))

    @filter.command("菜品排名")
    async def cmd_dish_rank(self, event: AstrMessageEvent):
        """菜品评分排名（可带参数：堂食/外卖）。"""
        yield event.plain_result(
            "🥘 " + fmt.safe(fmt.render_dish_ranking, self.store, "15", self._arg_label(event)))

    @filter.command("性价比")
    async def cmd_value(self, event: AstrMessageEvent):
        """菜品性价比排行（可带参数：堂食/外卖）。"""
        yield event.plain_result(
            "💰 " + fmt.safe(fmt.render_value_ranking, self.store,
                             "菜品", self._arg_label(event), ""))

    @filter.command("招牌菜")
    async def cmd_signature(self, event: AstrMessageEvent):
        """每家店的招牌菜排名（可带参数：堂食/外卖）。"""
        yield event.plain_result(
            "⭐ " + fmt.safe(fmt.render_signature, self.store, "15", self._arg_label(event)))

    def _arg_label(self, event: AstrMessageEvent) -> str:
        text = str(getattr(event, "message_str", "") or "")
        for kw in ("外卖", "堂食"):
            if kw in text:
                return kw
        return "全部"

    @filter.command("美食帮助")
    async def cmd_help(self, event: AstrMessageEvent):
        """查看吃了什么插件的所有指令与 app 配置说明。"""
        port = self.config.get("http_port", DEFAULT_PORT)
        text = (
            "📖 吃了什么 · 美食记录插件\n"
            "指令：\n"
            "  /吃什么 [堂食|外卖]   随机推荐一家店\n"
            "  /餐厅排名 [堂食|外卖]  餐厅评分排名\n"
            "  /菜品排名 [堂食|外卖]  菜品评分排名\n"
            "  /招牌菜 [堂食|外卖]   每家店的招牌菜\n"
            "  /性价比 [堂食|外卖]   菜品性价比排行\n\n"
            "手机 app「吃了什么」录入：服务器填 http://<本机IP>:" + str(port) +
            "，Token 与插件配置 api_token 一致。\n"
            "数据存放在 AstrBot 数据目录 data/astrbot_plugin_eat_what/。"
        )
        yield event.plain_result(text)
