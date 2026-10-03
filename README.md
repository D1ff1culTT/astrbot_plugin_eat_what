# astrbot_plugin_eat_what · 吃了什么

AstrBot 插件：个人美食记录 + AI 查询。手机 app 录入吃过的餐厅、菜品、评分、评价、照片（支持**堂食/外卖双轨**、**区块逐级分类**如 广州市/天河区/某大学/某饭堂/某窗口），数据存在 AstrBot 服务器上；插件同时向 AI 提供 LLM 工具，随时回答「我吃过什么、什么好吃、今天吃什么」。

```
┌─────────────┐  HTTP/JSON   ┌────────────────────────────────┐
│  手机 App    │ ───────────▶ │ AstrBot（服务器/Docker）        │
│ 吃了什么.apk │  录入/查询    │  astrbot_plugin_eat_what 插件   │
└─────────────┘              │   ├ web.py    aiohttp 录入端    │
                             │   ├ store.py  SQLite+业务逻辑   │
聊天 AI ◀── LLM工具 ───────── │   └ main.py   插件入口/指令      │
                             │     数据: data/astrbot_plugin_eat_what/
                             └────────────────────────────────┘
```

## 目录结构

```
astrbot_plugin_eat_what/
├── main.py               # 插件入口：生命周期、LLM 工具、聊天指令
├── store.py              # 数据层：SQLite + 区块树聚合 + 排名（不依赖 AstrBot，可独立测试）
├── web.py                # 手机 app 的 HTTP 录入端（aiohttp，不依赖 AstrBot）
├── fmt.py                # LLM 工具/指令的文本渲染（不依赖 AstrBot）
├── metadata.yaml         # 插件元数据
├── _conf_schema.json     # 插件配置（端口/Token/高德key）
├── requirements.txt      # aiohttp（AstrBot 通常已内置）
├── phone_app/            # 安卓 app 源码（Java，无第三方依赖）
├── tools/
│   ├── build_apk.py      # APK 离线构建（无 Gradle）
│   ├── test_server.py    # 本地联调：无 AstrBot 直接起 HTTP 服务
│   └── eatwhat.keystore  # 签名密钥（保留它才能覆盖安装）
└── out/EatWhat.apk       # app 构建产物
```

## 一、安装插件（服务器上的 AstrBot）

1. AstrBot WebUI → 插件管理 → 从路径/仓库安装本目录（含 `metadata.yaml`）。
2. 插件配置：
   - `http_port`：手机上传端口，默认 **8765**；
   - `api_token`：上传鉴权 Token（手机 app 的 ⚙ 里填同一个值；留空不鉴权，仅建议内网）；
   - `amap_key`（可选）：高德 Web 服务 key，填了「定位→地址」反查走高德，不填退回 OSM（国内可能不通，地址可手动填）。
3. 启用插件。启动日志会出现 `手机 app 录入端已启动: http://0.0.0.0:8765/api/health`。

**Docker 部署**：容器需映射端口，如 `docker run ... -p 8765:8765`；数据在容器内
`AstrBot/data/astrbot_plugin_eat_what/`（建议把 AstrBot 的 data 目录挂载到宿主机，备份只需拷走该目录）。

**本地开发联调**（不需要 AstrBot）：

```bash
python tools/test_server.py 8766    # 127.0.0.1:8766, token=test123, 数据在 .build/testdata/
```

## 二、手机 App

### 构建（无需 Android Studio / Gradle）

```bash
python tools/build_apk.py        # 产物 out/EatWhat.apk
```

依赖本机 JDK 8+ 与离线安卓工具链 `../astrbot_plugin_oppo_watch/_android_build`
（android.jar API27 / aapt / ECJ / d8 / apksigner），放别处可用环境变量
`EATWHAT_TOOLCHAIN` 指定。`tools/eatwhat.keystore` 已入库，**不要删除**，否则重打包后无法覆盖安装。

### 安装与首次使用

1. 把 `out/EatWhat.apk` 传到手机安装（或 USB：`adb install -r out/EatWhat.apk`，adb 在 oppo_watch 插件的 `_adb/platform-tools`）。
2. 打开「吃了什么」→ 右上角 **⚙** → 服务器地址填 `http://<AstrBot服务器IP>:8765`（与插件 `http_port` 一致），Token 与插件 `api_token` 一致 → 「测试连接」确认。

### 功能

- **记录**：店名（输入联想已记录餐厅）；「堂食 / 外卖」切换；「选择区块」下钻式挑选（任意层级、可随时新增，如 广州市/天河区/某大学/某饭堂/某窗口）；「📍 定位」自动填坐标并反查地址；整体评价；菜品卡片（菜名+星级+文字+📷 拍照/相册，照片自动压缩后上传）。
- **离线兜底**：连不上服务器时，整餐（含照片）自动存到手机本地，**无服务器也照常记录**；联网后自动补传（app 启动、网络恢复、保存成功后都会触发，⚙ 里也有「立即同步」和待同步计数）。列表/详情页离线时展示上次缓存的数据并显示「离线」横幅。
- **排行**：餐厅榜 / 菜品榜 / 招牌菜 ×（全部 / 堂食 / 外卖）筛选。
- **区块**：一级区块榜 → 逐级下钻（本级聚合评分、子区块排名、区块内餐厅排名）。
- **餐厅详情**：堂食/外卖分轨评分、区块路径、招牌菜排名、评价列表、地图呈现（高德 App/网页）。
- **今天吃什么**：按评分加权随机推荐。

> 评分聚合规则：整体评价均分优先，无整体评价时退回菜品评价均分；区块分数 = 子树内全部餐厅的聚合。堂食/外卖分别独立统计。
> 离线存储：待同步队列与照片在应用内部存储（`files/pending/`），响应缓存在本地 SQLite（`eatwhat_offline.db`）；被服务器明确拒绝的记录（如 Token 错误）不会进队列。

## 三、聊天指令与 LLM 工具

指令（可带参数 `堂食` / `外卖`）：`/吃什么`、`/餐厅排名`、`/菜品排名`、`/招牌菜`、`/美食帮助`。

AI 会话中可用的 LLM 工具：

| 工具 | 作用 |
|---|---|
| `restaurant_ranking` / `dish_ranking` / `signature_dishes` | 三类排名（支持堂食/外卖筛选） |
| `search_restaurant(keyword)` | 按店名/菜名/地址搜索吃过的餐厅 |
| `restaurant_detail(name)` | 店详情：分轨评分、区块、菜品排名、最近评价 |
| `area_query(name)` | 区块逐级查询（如「天河区下哪个饭堂评分最高」） |
| `recommend_food(mode)` | 「今天吃什么」加权随机推荐 |

## 四、HTTP API（手机 app 用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 存活 + 数量统计（无需鉴权） |
| POST | `/api/visits` | 记录一餐：餐厅（名称/地址/坐标/区块）+ 菜品评价[] + 整体评价 + `mode`（0堂食/1外卖）；`area_path` 逐级自动建区 |
| GET | `/api/restaurants?keyword=&limit=&mode=` | 餐厅列表/搜索（名称、地址、**菜名**均可匹配） |
| GET | `/api/restaurants/{id}` | 详情：菜品排名 + 最近评价 + 堂食/外卖分轨统计 + 区块路径 + 地图链接 |
| GET | `/api/areas?parent_id=&mode=` | 区块视图：路径、子区块（聚合评分排序）、子树内餐厅排名；`parent_id` 缺省=根 |
| POST | `/api/areas` | 新增区块 `{name, parent_id}` |
| GET | `/api/rank/restaurants?mode=` / `/api/rank/dishes?mode=` / `/api/rank/restaurant_dishes?mode=` | 三类排名 |
| GET | `/api/random?mode=&min_rating=` | 今天吃什么 |
| POST | `/api/upload` | 图片上传（multipart，≤10MB），返回 `/images/...` |
| GET | `/api/geocode?lat=&lng=` | 坐标→地址（高德/OSM，失败返回 null） |
| GET | `/api/export` | 全量数据导出（含区块树） |

> `mode`：`-1`=全部、`0`=堂食、`1`=外卖。鉴权：除 `/api/health` 与 `/images/*` 外均需 `Authorization: Bearer <token>` 或 `X-Api-Token`。
