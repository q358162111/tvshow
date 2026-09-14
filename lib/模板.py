# -*- coding: utf-8 -*-
# TVBox Python 源通用模板（T4 Spider）
# 使用方法：复制本文件并重命名，按 TODO 注释填入目标站点的接口/解析逻辑，
# 然后在配置 json 中按下方示例注册即可。
#
# 配置示例（放入点播配置的 sites 数组中）：
# {
#     "key": "demo",
#     "name": "示例影视",
#     "type": 3,
#     "api": "./lib/demo.py",          # 本文件路径
#     "searchable": 1,
#     "quickSearch": 1,
#     "filterable": 1,
#     "ext": {
#         "host": "https://www.example.com"   # 站点域名，可在配置里覆盖
#     }
# }
#
# 各接口返回值均为 dict，最终由框架序列化为 JSON。
# 影片列表条目字段：vod_id / vod_name / vod_pic / vod_remarks (vod_year 可选)
# 播放源格式：vod_play_from = "源1$$$源2"，vod_play_url = "第1集$url1#第2集$url2$$$第1集$url3"
# 其中 $$$ 分隔不同播放源，# 分隔同一源内的不同集数，$ 分隔集名与地址。

import sys
import re
import json
import base64
sys.path.append("..")
from base.spider import Spider


class Spider(Spider):

    # ==================== 基础配置 ====================

    # 默认站点域名，可被 ext.host 覆盖
    HOST = "https://www.example.com"

    # 站点请求头（按需修改 UA / Referer / Cookie 等）
    HEADERS = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "Referer": "https://www.example.com/",
    }

    # 是否启用本地代理处理 m3u8（图片防盗链 / 相对路径切片时开启）
    ENABLE_PROXY = False

    def getName(self):
        return "模板影视"

    def init(self, extend=""):
        # extend 为配置里 ext 字段的值（字符串或 dict）
        if extend:
            try:
                ext = json.loads(extend) if isinstance(extend, str) else extend
                if ext.get("host"):
                    self.HOST = ext["host"].rstrip("/")
            except Exception:
                pass
        # TODO: 初始化设备号、token、cookie 等站点需要的前置数据

    def isVideoFormat(self, url):
        # 告诉框架哪些 url 可以直接播放，无需嗅探
        return any(x in url for x in [".m3u8", ".mp4", ".mkv", ".avi", ".flv", ".mp3", ".m4a"])

    def manualVideoCheck(self):
        # 是否需要手动选择播放方式，一般保持 False
        return False

    def action(self, action):
        # 处理一级页面里 ext:// 开头的本地动作按钮，用不到可留空
        pass

    def destroy(self):
        pass

    # ==================== 首页 ====================

    def homeContent(self, filter):
        # 返回 {class: [...], filters: {...}}
        # TODO: 请求站点分类接口，填充 classes 与 filters
        classes = [
            # {"type_name": "电影", "type_id": "1"},
            # {"type_name": "剧集", "type_id": "2"},
        ]
        filters = {
            # "1": [
            #     {"key": "area", "name": "地区",
            #      "value": [{"n": "全部", "v": ""}, {"n": "大陆", "v": "大陆"}]},
            #     {"key": "year", "name": "年份",
            #      "value": [{"n": "全部", "v": ""}, {"n": "2025", "v": "2025"}]},
            # ],
        }
        return {"class": classes, "filters": filters}

    def homeVideoContent(self):
        # 返回 {list: [...]}，首页推荐位（可省略实现，返回空即可）
        # TODO: 请求站点首页推荐接口
        return {"list": []}

    # ==================== 分类 ====================

    def categoryContent(self, tid, pg, filter, extend):
        # tid: 分类id；pg: 页码(字符串)；extend: 用户所选筛选项 {"area": "...", "year": "..."}
        # 返回 {list, page, pagecount, limit, total}
        # TODO: 请求站点分类列表接口
        videos = [
            # self.to_vod({...}),
        ]
        return {
            "list": videos,
            "page": int(pg),
            "pagecount": 9999,   # 站点不返回总页数时可给大值
            "limit": 90,
            "total": 999999,
        }

    # ==================== 详情 ====================

    def detailContent(self, ids):
        # ids: [vod_id]，返回 {list: [vod]}
        # TODO: 请求站点详情接口，组装 vod 与播放列表
        vod = {
            "vod_id": ids[0],
            "vod_name": "",
            "vod_pic": "",
            "type_name": "",       # 分类名
            "vod_year": "",        # 年份
            "vod_area": "",        # 地区
            "vod_actor": "",       # 演员
            "vod_director": "",    # 导演
            "vod_remarks": "",     # 更集状态
            "vod_content": "",    # 简介
            "vod_play_from": "默认源",
            "vod_play_url": "",    # 形如 "第1集$http://xxx/1.m3u8#第2集$http://xxx/2.m3u8"
        }
        return {"list": [vod]}

    # ==================== 搜索 ====================

    def searchContent(self, key, quick, pg=1):
        # 返回 {list, page}（部分框架要求 quick 参数，保持签名不变）
        # TODO: 请求站点搜索接口
        videos = []
        return {"list": videos, "page": int(pg)}

    # ==================== 播放 ====================

    def playerContent(self, flag, id, vipFlags):
        # flag: 播放源名；id: 播放地址（detailContent 里 $ 后面的部分）
        # 返回 {parse, url, header, ...}，parse=0 表示 url 为直链
        url = id
        # TODO: 若站点播放页是网页需要二次解析，在此处理，例如：
        # html = self.fetch(url, headers=self.HEADERS).text
        # m = re.search(r"url\s*[:=]\s*['\"]([^'\"]+\.m3u8)", html)
        # if m:
        #     url = m.group(1)
        result = {
            "parse": 0 if self.isVideoFormat(url) else 1,
            "url": url,
            "header": {"User-Agent": self.HEADERS["User-Agent"], "Referer": f"{self.HOST}/"},
        }
        return result

    # ==================== 本地代理（可选） ====================
    # 用于处理 m3u8 相对路径、图片防盗链等，开启需在配置加 "ext": {"proxy": 1}

    def localProxy(self, param):
        # getProxyUrl() 生成的链接会回调到这里，param 为 query 参数 dict
        if param.get("type") == "m3u8":
            url = base64.b64decode(param["url"]).decode("utf-8")
            base_dir = url[:url.rfind("/")]
            data = self.fetch(url, headers=self.HEADERS).content.decode("utf-8", "ignore")
            lines = []
            for line in data.strip().split("\n"):
                if "#EXT" not in line and line and "http" not in line:
                    line = base_dir + ("" if line.startswith("/") else "/") + line
                lines.append(line)
            return [200, "application/vnd.apple.mpegurl", "\n".join(lines)]
        if param.get("type") == "img":
            url = base64.b64decode(param["url"]).decode("utf-8")
            img = self.fetch(url, headers={"User-Agent": self.HEADERS["User-Agent"], "Referer": f"{self.HOST}/"}).content
            return [200, "image/jpeg", img]
        return [200, "text/plain", ""]

    def proxy_m3u8(self, url):
        # 列表里图片/播放地址需走代理时，用该方法生成代理地址
        if not self.ENABLE_PROXY:
            return url
        return self.getProxyUrl() + "&url=" + base64.b64encode(url.encode()).decode() + "&type=m3u8"

    # ==================== 工具方法 ====================

    def to_vod(self, item):
        # 统一将站点原始数据转成列表条目，TODO: 按站点实际字段名映射
        return {
            "vod_id": item.get("id"),
            "vod_name": item.get("name", ""),
            "vod_pic": item.get("pic", ""),
            "vod_year": item.get("year", ""),
            "vod_remarks": item.get("remarks", ""),
        }

    # self.fetch(url, headers=..., method=..., body=..., timeout=...)
    #     框架内置请求方法，返回 requests.Response（有 .text/.json()/.content/.headers）
    # self.post(url, headers=..., body=...)  POST 快捷方式
    # self.getProxyUrl()  获取 localProxy 回调地址
    # 其它可用：self.md5 / self.sha1 / self.aes / self.base64Decode 等（见 base.spider）
