# -*- coding: utf-8 -*-
"""独立启动插件 HTTP 服务（不需要 AstrBot），用于本地联调手机 app / 回归测试。

用法:
  python tools/test_server.py [port]        # 默认 127.0.0.1:8766，token=test123
  数据放在 .build/testdata/，可随时整个删除。
"""
import asyncio
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))

from store import EatWhatStore          # noqa: E402
from web import EatWhatService          # noqa: E402


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    data_dir = REPO / ".build" / "testdata"
    store = EatWhatStore(db_path=data_dir / "eat_what.db", images_dir=data_dir / "images")
    service = EatWhatService(store, "127.0.0.1", port,
                             api_token="test123", amap_key="")

    async def run():
        await service.start()
        print(f"EATWHAT TEST SERVER http://127.0.0.1:{port}  (token=test123, data={data_dir})")
        while True:
            await asyncio.sleep(3600)

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
