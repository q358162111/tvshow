# -*- coding: utf-8 -*-
# TV电影天堂 www.tvdy.xyz  TVBox Python 源(T4)
# 站点架构：苹果CMS10 default模板
# 分类: /vodshow/{id}---{year}-{class}-{area}-{lang}----{page}---.html
#       段位: 1=id 4=year 5=class(剧情) 6=area(地区) 7=lang(语言) 11=page
# 搜索: /vodsearch/{wd}----------{page}---.html
# 详情: /voddetail/{id}.html    播放: /vodplay/{id}-{sid}-{nid}.html
#
# 配置示例：
# {
#     "key": "tvdy",
#     "name": "TV电影天堂",
#     "type": 3,
#     "api": "./lib/TV电影天堂.py",
#     "searchable": 1,
#     "quickSearch": 1,
#     "filterable": 1,
#     "ext": {"host": "https://www.tvdy.xyz"}
# }

import sys
import re
import json
import base64
sys.path.append("..")
from base.spider import Spider
from urllib.parse import quote, unquote


class Spider(Spider):

    HOST = "https://www.tvdy.xyz"

    HEADERS = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer": "https://www.tvdy.xyz/",
    }

    # 筛选值（来自站点列表页实测；"全部"传空）
    CLS = ["全部", "喜剧", "动作", "剧情", "爱情", "科幻", "悬疑", "惊悚", "恐怖", "犯罪",
           "同性", "音乐", "歌舞", "传记", "历史", "战争", "西部", "奇幻", "冒险", "灾难", "武侠", "短剧"]
    AREA = ["全部", "大陆", "香港", "台湾", "美国", "法国", "英国", "日本", "韩国", "泰国",
            "德国", "丹麦", "印度", "意大利", "西班牙", "菲律宾", "加拿大", "其它"]
    LANG = ["全部", "国语", "粤语", "英语", "韩语", "日语", "法语", "德语", "俄语", "泰语",
            "闽南语", "意大利语", "西班牙语", "葡萄牙语", "菲律宾语", "泰米尔语", "其它"]
    YEAR = ["全部"] + [str(y) for y in range(2026, 2009, -1)] + ["更早"]

    def getName(self):
        return "TV电影天堂"

    def init(self, extend=""):
        if extend:
            try:
                ext = json.loads(extend) if isinstance(extend, str) else extend
                if ext.get("host"):
                    self.HOST = ext["host"].rstrip("/")
                    self.HEADERS["Referer"] = self.HOST + "/"
            except Exception:
                pass

    def isVideoFormat(self, url):
        return any(x in url for x in [".m3u8", ".mp4", ".mkv", ".avi", ".flv"])

    def manualVideoCheck(self):
        return False

    def action(self, action):
        pass

    def destroy(self):
        pass

    # ==================== 网络与工具 ====================

    def get(self, path):
        r = self.fetch(self.HOST + path, headers=self.HEADERS)
        return r.content.decode("utf-8", "ignore")

    @staticmethod
    def attr(attrs, name):
        m = re.search(name + r'="([^"]*)"', attrs)
        return m.group(1) if m else ""

    def fix(self, u):
        if not u:
            return ""
        if u.startswith("//"):
            return "https:" + u
        if u.startswith("/"):
            return self.HOST + u
        return u

    @staticmethod
    def clean(text):
        t = re.sub(r"<[^>]+>", " ", text)
        return re.sub(r"\s+", " ", t).strip(" ：:，,、")

    # ==================== 首页 ====================

    def homeContent(self, filter):
        classes = []
        # 解析首页导航获取分类，失败则用默认
        try:
            html = self.get("/")
            seen = set()
            for m in re.finditer(
                r'<a\b[^>]*href="[^"]*?/(?:vodshow/(\d+)[-/]|(?:vod)?type/(\d+)[./])[^"]*"[^>]*>\s*([^<>]{1,8})\s*</a>',
                html,
            ):
                tid = m.group(1) or m.group(2)
                name = re.sub(r"<[^>]+>", "", m.group(3)).strip()
                if not tid or not name or tid in seen or name in ("首页", "更新", "网址", "更多"):
                    continue
                seen.add(tid)
                classes.append({"type_id": tid, "type_name": name})
        except Exception:
            pass
        if not classes:
            classes = [{"type_id": "1", "type_name": "电影"}, {"type_id": "2", "type_name": "电视剧"},
                       {"type_id": "3", "type_name": "综艺"}, {"type_id": "4", "type_name": "动漫"},
                       {"type_id": "5", "type_name": "体育"}]

        def opts(values):
            return [{"n": v, "v": "" if v == "全部" else v} for v in values]

        filters = {}
        for c in classes:
            filters[c["type_id"]] = [
                {"key": "class", "name": "剧情", "value": opts(self.CLS)},
                {"key": "area", "name": "地区", "value": opts(self.AREA)},
                {"key": "lang", "name": "语言", "value": opts(self.LANG)},
                {"key": "year", "name": "年份", "value": opts(self.YEAR)},
            ]
        return {"class": classes, "filters": filters}

    def homeVideoContent(self):
        try:
            html = self.get("/")
            return {"list": self.parse_list(html)}
        except Exception:
            return {"list": []}

    # ==================== 分类 ====================

    def categoryContent(self, tid, pg, filter, extend):
        extend = extend or {}
        try:
            pg = int(pg)
        except Exception:
            pg = 1

        def seg(key):
            v = extend.get(key, "")
            return "" if (not v or v == "全部") else v

        url = (f"/vodshow/{tid}---{seg('year')}-{seg('class')}-{seg('area')}"
               f"-{seg('lang')}----{pg}---.html")
        try:
            html = self.get(url)
            videos = self.parse_list(html)
        except Exception:
            videos = []
        return {"list": videos, "page": pg, "pagecount": 9999, "limit": 90, "total": 999999}

    # ==================== 搜索 ====================

    def searchContent(self, key, quick, pg=1):
        try:
            pg = int(pg)
        except Exception:
            pg = 1
        html = self.get(f"/vodsearch/{quote(key)}----------{pg}---.html")
        return {"list": self.parse_list(html), "page": pg}

    # ==================== 详情 ====================

    def detailContent(self, ids):
        vid = ids[0]
        html = self.get(f"/voddetail/{vid}.html")

        name = (re.search(r"<h1[^>]*>([^<]+)</h1>", html) or
                re.search(r"<title>《(.+?)》", html))
        pic = (re.search(r'class="[^"]*thumb[^"]*"[^>]*data-original="([^"]+)"', html) or
               re.search(r'class="[^"]*thumb[^"]*"[^>]*src="([^"]+)"', html) or
               re.search(r'data-original="([^"]+\.(?:jpg|jpeg|png|webp))"', html))

        def seg(label):
            m = re.search(label + r'\s*[:：]?\s*(?:</?\w+[^>]*>)*\s*([\s\S]{0,800}?)</(?:p|div|dd|li|ul)>', html)
            if not m:
                return ""
            return self.clean(m.group(1))

        content = ""
        cm = (re.search(r'class="[^"]*(?:vod_content|content|desc)[^"]*"[^>]*>([\s\S]{0,2000}?)</(?:p|div|span)>', html))
        if cm:
            content = self.clean(cm.group(1))
        if not content:
            content = seg("简介")

        vod = {
            "vod_id": vid,
            "vod_name": name.group(1).strip() if name else vid,
            "vod_pic": self.fix(pic.group(1)) if pic else "",
            "vod_actor": seg("主演"),
            "vod_director": seg("导演"),
            "vod_area": seg("地区"),
            "vod_year": seg("年份"),
            "vod_remarks": seg("状态"),
            "vod_content": content,
        }

        # 播放源名称: <a href="#playlist1">xxx</a>
        names = {}
        for m in re.finditer(r'<a\b[^>]*href="#playlist(\d+)"[^>]*>([\s\S]*?)</a>', html):
            n = self.clean(m.group(2))
            if n:
                names[int(m.group(1))] = n

        # 分集链接: /vodplay/{id}-{sid}-{nid}.html
        eps = {}
        for m in re.finditer(
                r'<a\b[^>]*href="([^"]*?/vodplay/\d+-(\d+)-\d+\.html?)"[^>]*>\s*(?:<[^>]+>)*([^<>]*?)\s*(?:<[^>]+>)*</a>',
                html):
            sid = int(m.group(2))
            title = m.group(3).strip()
            if not title:
                continue
            eps.setdefault(sid, []).append((title, m.group(1)))

        froms, urls = [], []
        for sid in sorted(eps):
            froms.append(names.get(sid) or f"线路{sid}")
            urls.append("#".join(f"{t}${u}" for t, u in eps[sid]))
        vod["vod_play_from"] = "$$$".join(froms) if froms else "暂无资源"
        vod["vod_play_url"] = "$$$".join(urls) if urls else ""
        return {"list": [vod]}

    # ==================== 播放 ====================

    def playerContent(self, flag, id, vipFlags):
        url = id
        header = {"User-Agent": self.HEADERS["User-Agent"], "Referer": self.HOST + "/"}
        if url.startswith("/vodplay/") or not url.startswith("http"):
            try:
                html = self.get(url if url.startswith("/") else "/" + url)
                # 苹果CMS标准: var player_aaa = {"url":"...","encrypt":0,...}
                m = re.search(r"player_aaa\s*=\s*(\{.*?\})\s*;?\s*(?:</script>|var\b|function\b|$)",
                              html, re.S)
                if m:
                    data = json.loads(m.group(1))
                    u = data.get("url", "")
                    if u:
                        enc = int(data.get("encrypt", 0) or 0)
                        if enc == 1:
                            u = unquote(u)
                        elif enc == 2:
                            u = base64.b64decode(u).decode("utf-8", "ignore")
                        url = u
                # 兜底: iframe 播放页
                if not re.search(r"\.m3u8|\.mp4", url):
                    im = re.search(r'<iframe[^>]*src="([^"]+)"', html)
                    if im:
                        url = im.group(1)
            except Exception:
                pass
        result = {"parse": 0 if self.isVideoFormat(url) else 1,
                  "url": url, "header": header}
        return result

    # ==================== 列表解析 ====================

    def parse_list(self, html):
        vods, seen = [], set()
        for m in re.finditer(r"<a\b([^>]*)>", html):
            attrs = m.group(1)
            hm = re.search(r'href="([^"]*?/voddetail/(\d+)\.html?)"', attrs)
            if not hm:
                continue
            vid = hm.group(2)
            if vid in seen:
                continue
            name = (re.search(r'title="([^"]*)"', attrs) or
                    re.search(r'aria-label="([^"]*)"', attrs))
            if not name:
                continue
            pic = (re.search(r'data-original="([^"]*)"', attrs) or
                   re.search(r'data-src="([^"]*)"', attrs) or
                   re.search(r'src="([^"]*)"', attrs))
            seen.add(vid)
            tail = html[m.end(): m.end() + 600]
            rm = re.search(r'<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]+)<', tail)
            vods.append({
                "vod_id": vid,
                "vod_name": name.group(1).strip(),
                "vod_pic": self.fix(pic.group(1)) if pic else "",
                "vod_remarks": rm.group(1).strip() if rm else "",
            })
        return vods
