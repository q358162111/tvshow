# -*- coding: utf-8 -*-
# TV电影天堂 www.tvdy.xyz  TVBox Python 源(T4)
# 站点架构：苹果CMS10 stui 模板（所有 URL 段位均经实测验证）
#
# 分类: /vodshow/{id}-{area}-{by}-{class}-{lang}----{page}---{year}.html
#       12段11横线 段位: 1=id(拼音) 2=area 3=by(time/hits/level/score) 4=class 5=lang 9=page 12=year
# 分类首页: /vodtype/{id}.html (如 dianshiju)  导航分类: /vodtype/dianying.html
# 搜索: /vodsearch/{wd}----------{page}---.html
# 详情: /voddetail/{id}.html    播放: /vodplay/{id}-{sid}-{nid}.html
# 播放数据: var player_aaaa = {...} encrypt=2 时 url 为 base64(urlencode(直链))
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

    # 筛选值（实测自站点列表页；"全部"传空）
    CLS = ["全部", "喜剧", "动作", "剧情", "爱情", "科幻", "悬疑", "惊悚", "恐怖", "犯罪",
           "同性", "音乐", "歌舞", "传记", "历史", "战争", "西部", "奇幻", "冒险", "灾难", "武侠", "短剧"]
    AREA = ["全部", "大陆", "香港", "台湾", "美国", "法国", "英国", "日本", "韩国", "泰国",
            "德国", "丹麦", "印度", "意大利", "西班牙", "菲律宾", "加拿大", "其它"]
    LANG = ["全部", "国语", "粤语", "英语", "韩语", "日语", "法语", "德语", "俄语", "泰语",
            "闽南语", "意大利语", "西班牙语", "葡萄牙语", "菲律宾语", "泰米尔语", "其它"]
    YEAR = ["全部"] + [str(y) for y in range(2026, 2009, -1)] + ["更早"]
    BY = [("全部", ""), ("最新", "time"), ("人气", "hits"), ("推荐", "level"), ("评分", "score")]

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
    def clean(text):
        t = re.sub(r"<[^>]+>", " ", text)
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
        return re.sub(r"\s+", " ", t).strip(" ：:，,、")

    def fix(self, u):
        if not u:
            return ""
        if u.startswith("//"):
            return "https:" + u
        if u.startswith("/"):
            return self.HOST + u
        return u

    # ==================== 首页 ====================

    def homeContent(self, filter):
        classes = []
        # 从首页导航解析分类: /vodtype/{拼音}.html
        try:
            html = self.get("/")
            seen = set()
            for m in re.finditer(r'<a\b[^>]*href="/vodtype/([a-z0-9]+)\.html"[^>]*>\s*([^<>]{1,8})\s*</a>', html):
                tid, name = m.group(1), m.group(2).strip()
                if not name or tid in seen or name in ("首页", "更新", "网址", "更多"):
                    continue
                seen.add(tid)
                classes.append({"type_id": tid, "type_name": name})
        except Exception:
            pass
        if not classes:
            classes = [{"type_id": t, "type_name": n} for t, n in [
                ("dianying", "电影"), ("dianshiju", "电视剧"),
                ("zongyi", "综艺"), ("dongman", "动漫"), ("tiyu", "体育")]]

        def opts(values):
            return [{"n": v, "v": "" if v == "全部" else v} for v in values]

        filters = {}
        for c in classes:
            filters[c["type_id"]] = [
                {"key": "class", "name": "剧情", "value": opts(self.CLS)},
                {"key": "area", "name": "地区", "value": opts(self.AREA)},
                {"key": "lang", "name": "语言", "value": opts(self.LANG)},
                {"key": "year", "name": "年份", "value": opts(self.YEAR)},
                {"key": "by", "name": "排序", "value": [
                    {"n": n, "v": v} for n, v in self.BY]},
            ]
        return {"class": classes, "filters": filters}

    def homeVideoContent(self):
        try:
            return {"list": self.parse_list(self.get("/"))}
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

        # 12段: id-area-by-class-lang-空-空-空-page-空-空-year
        parts = [tid, seg("area"), seg("by"), seg("class"), seg("lang"),
                 "", "", "", str(pg), "", "", seg("year")]
        url = "/vodshow/" + "-".join(quote(p) if p else p for p in parts) + ".html"
        try:
            html = self.get(url)
            videos = self.parse_list(html)
            # 总页数: 页面分页区有 "1/137" 样式
            pagecount = 9999
            pm = re.search(r'>(\d+)\s*/\s*(\d+)<', html)
            if pm and int(pm.group(2)) > int(pm.group(1)) - 1:
                pagecount = int(pm.group(2))
        except Exception:
            videos, pagecount = [], 9999
        return {"list": videos, "page": pg, "pagecount": pagecount, "limit": "90", "total": 999999}

    # ==================== 搜索 ====================

    def searchContent(self, key, quick, pg=1):
        try:
            pg = int(pg)
        except Exception:
            pg = 1
        try:
            html = self.get(f"/vodsearch/{quote(key)}----------{pg}---.html")
            return {"list": self.parse_list(html), "page": pg}
        except Exception:
            return {"list": [], "page": pg}

    # ==================== 详情 ====================

    def detailContent(self, ids):
        vid = ids[0]
        html = self.get(f"/voddetail/{vid}.html")

        name = re.search(r"<title>《(.+?)》", html)
        pic = re.search(r'class="stui-content__thumb[^"]*"[^>]*>.*?data-original="([^"]+)"', html, re.S) \
            or re.search(r'data-original="([^"]+\.(?:jpg|jpeg|png|webp))"', html)

        # 字段区: <p class="data">类型：xxx</p> <p class="data">主演：xxx</p> ...
        data = {}
        for m in re.finditer(r'<p class="data">([^：:<]+)[：:]([\s\S]*?)</p>', html):
            key = m.group(1).strip()
            if key and key not in data:
                data[key] = self.clean(m.group(2))

        # 简介: <span class="detail-content">正文</span>，为空时取 detail-sketch
        cm = re.search(r'<span class="detail-content"[^>]*>([\s\S]*?)</span>', html)
        if not cm or cm.group(1).strip() in ("", "..."):
            cm = re.search(r'<span class="detail-sketch"[^>]*>([\s\S]*?)</span>', html)
        content = self.clean(cm.group(1)) if cm else ""
        if content == "...":
            content = ""

        vod = {
            "vod_id": vid,
            "vod_name": name.group(1).strip() if name else vid,
            "vod_pic": self.fix(pic.group(1)) if pic else "",
            "vod_type": data.get("类型", ""),
            "vod_actor": data.get("主演", ""),
            "vod_director": data.get("导演", ""),
            "vod_area": data.get("地区", ""),
            "vod_remarks": data.get("更新", ""),
            "vod_content": content,
        }

        # 播放源: <h4>...播放地址N</h4><ul>...<li><a href="/vodplay/x-y-z.html">第01集</a></li>...</ul>
        froms, urls = [], []
        for m in re.finditer(r'<h4[^>]*>(?:<[^>]+>|&nbsp;|\s)*([^<>]*?)(?:<[^>]+>|\s)*</h4>\s*<ul[^>]*>([\s\S]*?)</ul>', html):
            src_name = m.group(1).strip()
            if "播放" not in src_name:
                continue
            eps = re.findall(r'<a\b[^>]*href="(/vodplay/\d+-\d+-\d+\.html)"[^>]*>([^<]*)</a>', m.group(2))
            eps = [(t.strip(), u) for u, t in eps if t.strip()]
            if not eps:
                continue
            froms.append(src_name)
            urls.append("#".join(f"{t}${u}" for t, u in eps))
        vod["vod_play_from"] = "$$$".join(froms) if froms else "暂无资源"
        vod["vod_play_url"] = "$$$".join(urls)
        return {"list": [vod]}

    # ==================== 播放 ====================

    def playerContent(self, flag, id, vipFlags):
        url = id
        header = {"User-Agent": self.HEADERS["User-Agent"], "Referer": self.HOST + "/"}
        try:
            html = self.get(url)
            # 苹果CMS stui 模板: var player_aaaa = {...} (注意是四个a)
            m = re.search(r"player_aaaa\s*=\s*(\{.*?\})\s*</script>", html, re.S)
            if m:
                data = json.loads(m.group(1))
                u = data.get("url", "")
                if u:
                    enc = int(data.get("encrypt", 0) or 0)
                    if enc == 1:
                        u = unquote(u)
                    elif enc == 2:
                        # 双重编码: base64 -> urlencode
                        try:
                            u = unquote(base64.b64decode(u).decode("utf-8", "ignore"))
                        except Exception:
                            u = base64.b64decode(u).decode("utf-8", "ignore")
                    url = u
        except Exception:
            pass
        return {"parse": 0 if self.isVideoFormat(url) else 1, "url": url, "header": header}

    # ==================== 列表解析 ====================

    def parse_list(self, html):
        vods, seen = [], set()
        # 卡片: <a class="stui-vodlist__thumb lazyload" href="/voddetail/{id}.html" title="名称" data-original="图片">
        for m in re.finditer(
                r'<a\b[^>]*class="stui-vodlist__thumb[^"]*"[^>]*>', html):
            attrs = m.group(0)
            hm = re.search(r'href="/voddetail/(\d+)\.html?"', attrs)
            if not hm:
                continue
            vid = hm.group(1)
            if vid in seen:
                continue
            name = (re.search(r'title="([^"]*)"', attrs))
            pic = (re.search(r'data-original="([^"]*)"', attrs) or
                   re.search(r'src="([^"]*)"', attrs))
            if not name:
                continue
            seen.add(vid)
            tail = html[m.end(): m.end() + 300]
            rm = re.search(r'<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]+)<', tail)
            vods.append({
                "vod_id": vid,
                "vod_name": name.group(1).strip(),
                "vod_pic": self.fix(pic.group(1)) if pic else "",
                "vod_remarks": rm.group(1).strip() if rm else "",
            })
        return vods
