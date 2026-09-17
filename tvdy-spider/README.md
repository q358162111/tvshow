# TV电影天堂 (https://www.tvdy.xyz) jar 爬虫

TVBox / 影视TV 的 **jar 类型爬虫**（catvod 规范），类名 `com.github.catvod.spider.TvDy`，对应配置里写 `"api": "csp_TvDy"`。

jar 内同时包含大小写兼容别名类 `com.github.catvod.spider.Tvdy`，因此 `"api": "csp_TvDy"` 与 `"api": "csp_Tvdy"` 都可用。

站点为 **苹果CMS10 + stui 模板**，本爬虫全部接口与 URL 段位均已对线上站点实测验证。

## 一、产物

- `dist/TvDy.jar` —— 可直接使用的爬虫 jar（内含 `classes.dex`，约 8 KB）

> 说明：`classes.dex` 里的 `com.github.catvod.crawler.Spider`、`OkHttp`、`org.json` **不打包**，运行时由 TVBox 宿主提供，与官方 `custom_spider.jar` 的机制一致。

## 二、目录结构

```
tvdy-spider/
├─ src/com/github/catvod/spider/TvDy.java       电影天堂 www.tvdy.xyz（stui 模板）
├─ src/com/github/catvod/spider/Movietv88.java  88影视（stui 模板）
├─ src/com/github/catvod/spider/Kanys8.java     看影视（苹果CMS，模拟 App 接口）
├─ src/com/github/catvod/spider/NetflixGc.java  奈飞工厂 netflixgc.org（dsn2 模板）
├─ src/com/github/catvod/spider/Vv3.java        vv3nwjk.com（Next.js flight 数据 + 接口签名）
├─ src/com/github/catvod/spider/Kky.java        可可影视 www.kkys04.com（含 JS 反爬破解）
├─ src/com/github/catvod/spider/YongLe.java      永乐视频 www.cw2.net（苹果CMS mxtheme 模板，页面伪装"瓜子影视"）
├─ src/com/github/catvod/spider/GuaZi.java       瓜子影视 api.bp7kprw.com（App 封闭签名接口：RSA+AES+MD5 签名 + Walle 渠道头）
├─ src/com/github/catvod/spider/Init.java       空 init，仅为通过宿主 JarLoader 校验
├─ src-alias/.../Tvdy.java                      大小写别名类（独立目录，规避 Windows 文件系统大小写不敏感）
├─ stubs/                                       编译期桩类，不会打进 jar
├─ test/TestSpider.java                         本地联调测试台（不参与打包）
├─ build.ps1                                    一键编译打包（javac → d8 → jar）
├─ dist/TvDy.jar                                构建产物
└─ build/                                       中间产物（已 gitignore）
```

## 三、重新构建

要求：JDK 8+（`javac`/`jar`/`java` 在 PATH 中）。首次运行会自动下载 `org.json` 和 `d8(r8)` 到 `build/lib/` 缓存。

```powershell
powershell -ExecutionPolicy Bypass -File tvdy-spider\build.ps1
```

流程：编译桩类 → 编译爬虫 → d8 转 `classes.dex` → 打包 `dist/TvDy.jar`。

## 四、配置用法

把 `dist/TvDy.jar` 放到 x.json 同目录（推荐相对路径），站点配置：

```json
{
  "key": "电影天堂",
  "name": "电影天堂",
  "type": 3,
  "api": "csp_TvDy",
  "searchable": 1,
  "quickSearch": 1,
  "filterable": 1,
  "jar": "./TvDy.jar"
}
```

- 域名写死为 `https://www.tvdy.xyz`；如需自定义，加 `"ext": {"host": "https://www.tvdy.xyz"}`。
- 若配置里习惯小写 `csp_Tvdy`，把 `TvDy.java` 的**文件名与类名**改成 `Tvdy` 再重新构建即可（Windows 下两个类名不能共存，会互相覆盖）。

## 五、实现要点

| 功能 | 站点规则 |
| --- | --- |
| 首页 | `/`，导航 `<ul class="stui-header__menu">` 解析出 分类(电影/电视剧/综艺/动漫/体育) |
| 列表 | `a.stui-vodlist__thumb` → `href=/voddetail/{id}.html`、`title`、`data-original`、`span.pic-text`(备注) |
| 分类 | `/vodshow/{id}-{area}-{by}-{class}-{lang}----{page}---{year}.html`（12 段），失败兜底 `/vodtype/{id}-{page}.html` |
| 筛选 | 剧情 / 地区 / 语言 / 年份 / 排序，由 `extend` 映射到上面的 URL 段位 |
| 搜索 | `/vodsearch/{关键词}----------{page}---.html` |
| 详情 | `/voddetail/{id}.html`：`<h1 class="title">`、`<p class="data">`(类型/主演/导演/地区/更新)、`span.detail-content`(简介)、`<h4>播放地址N</h4>+ul.stui-content__playlist` |
| 播放 | `/vodplay/{id}-{sid}-{nid}.html` 内 `var player_aaaa={...}`；`encrypt=2` 时 `url = base64 → urlencode → m3u8 直链` |

分页总数取分页区的 `当前页/总页数` 文本。

## 六、实测结果（构建前验证）

```
首页列表      23 条
分类 dianying 12 条 / 共 3226 页
筛选 大陆+喜剧+国语+2024  12 条 / 共 7 页
详情 73205    小巷人家 2024，2 个播放源 × 40 集
搜索 深渊     12 条
播放 73205-1-1  https://v.lzcdn28.com/20250923/2454_10e7242e/index.m3u8
```

瓜子影视（`csp_GuaZi`，2026-09-17 实测）：

```
分类      11 个（电影/连续剧/综艺/动漫/短剧/AI漫剧…），每类带 类型+地区+年份+排序 四组筛选
列表      电影 tid=1  total=42840；筛选 日本+2024+最热 正常返回 30 条/页
详情      勿言推理 电影版 → 官方线路1，HD$vod_d_id=12&vurl_id=507104&domain_type=8&resolution=1080&type=play
播放      1280/1920 直链 m3u8，片长 7740s / 775 段（正片；用浏览器 UA 请求只会拿到 20 秒宣传片）
首页      冷启动 2811ms（12 次接口并发）/ 热缓存 2ms；homeVideoContent 648ms
搜索      凡人修仙传 → 6 条（含剧版/重制版/燕家堡之战）
```

## 七、jar 内其他站点

同一个 `TvDy.jar` 里可放多个爬虫，配置里用 `"api": "csp_<类名>"` 区分（`jar` 字段都指向同一个文件）。

| 站点 | 类名 / api | 首页 | 分类 | 搜索 | 播放 |
| --- | --- | --- | --- | --- | --- |
| 奈飞工厂 `netflixgc.org` | `csp_NetflixGc` | dsn2 模板静态解析 | `POST /index.php/ds_api/vod`（JSON） | `/vodsearch/…?wd=` | `/vodplay/{id}-{sid}-{nid}.html` → `player_aaaa.url`（base64→urlencode） |
| vv3nwjk `vv3nwjk.com` | `csp_Vv3` | Next.js flight 数据 | `/vod/show/id/{tid}[/page/{n}]` | `/vod/search/{kw}`（仅一页） | 接口 `/mw-movie/anonymous/v2/video/episode/url`，需 `t` + `sign=sha1(md5(params&key&t))` |
| 可可影视 `www.kkys04.com` | `csp_Kky` | JS 反爬 cookie | `/show/{tid}-{class}-{area}-{lang}-{year}-{order}-{page}.html` | `/search?k={kw}&page={n}&t={token}`（token 取自 `/search`） | `/play/{id}-{sid}-{nid}.html` → `const playSource = {src:"…m3u8"}` |
| 永乐视频 `www.cw2.net`（页面伪装"瓜子影视"，代码为 ylsp 永乐系） | `csp_YongLe` | mxtheme 模板静态解析（Cloudflare CDN） | `/vodshow/{id}-{area}-{by}-{class}-{lang}-{letter}-..-{page}-..-{year}/`（12 段） | `/vodsearch/{kw}----------{page}---/` | `/watch/{id}-{sid}-{nid}/` → `player_aaaa.url`（encrypt=0 直链） |
| 瓜子影视 `api.bp7kprw.com` | `csp_GuaZi` | `/App/Resource/VodType/show` + `/App/IndexList/index` | `POST /App/IndexList/indexList`（`tid/page/pageSize/sub/sort/area/year`） | `POST /App/Index/findMoreVod` | `/App/Resource/VurlDetail/showOne` → 直链 m3u8 |

**镜像选择**：经实测 `cw2.net` 是唯一拥有完整片库与播放的入口；同模板的 `ylys.tv / ylsp.pro / ylsp.one / ylys.cc` 均为推广首页（详情/播放 404）。`ext` 接受 `{"host":"https://www.cw2.net"}` 或裸 `https://...` 临时切换调试。

`Vv3` 与 `Kky` 都支持 `"ext": {"host": "https://域名"}` 覆盖域名。

**Kky 的反爬说明**：首次访问返回 HTTP 850 + 一段混淆脚本，爬虫会在本地复刻该脚本（数组右旋 → 取 `cc` 与前缀 → 暴力求解最小 `i` 使 `sha1(cc+i)` 的两个字节匹配），算出 `cdndefend_js_cookie` 后带 cookie 重试。若站点更换脚本结构，`solveChallenge()` 里的正则需要同步调整。

### 7.1 瓜子影视（GuaZi）封闭签名接口

接口来自 App（瓜子影视 v3.0.5.2）反编译 + 抓包，**没有网页版**，全部请求为 POST，参数走表单体：

```
request_key = HEX( AES/CBC/PKCS5( JSON(params), key, iv ) )        key/iv 每次 16 位随机
keys        = BASE64( RSA/ECB/PKCS1( {"key":..,"iv":..} ) )       服务端用它的公钥加密
signature   = UPPER(MD5( "token_id=,token=,phone_type=1,request_key=..,app_id=1,time=..,keys=.."
                          + "*" + "&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br" ))
```

- **签名密钥**取自 `RetrofitHelper.f7381e`；`time` 为秒级时间戳；`request_key`/`keys` 必须与签名串里出现的**完全一致**。
- **必需请求头 `code`**：Walle 打包渠道号，直接从 APK Signing Block 读取（block id `0x71777777`，内容 `{"channel":"GZ0001"}`）。**缺失该头时所有业务接口一律返回 401「非官方渠道安装，无法访问」**，这是接入时最容易卡住的一步。
- **响应解密**：`data.keys` 用内置 RSA 私钥解出本次会话 `{key,iv}`，再用它 AES 解密 `data.response_key`（HEX 密文）得到业务 JSON。
- **token**：匿名设备注册 `/App/Authentication/Device/signUp`（`old_key`/`new_key`/`phone_type`/`code`），返回的 `token` 全站复用；爬虫在 token 失效时会自动重注册一次。
- **详情只有线路**：`/App/Resource/Vod/showOne?d_id=` 只返回 `vurl_clouds`（线路）与会员标签，**不带片名/海报/演员**（App 自身也是从列表页带过去的）。因此爬虫内建 600 条列表缓存，`detailContent` 时回捞；收藏夹等冷启动场景会退化为仅显示线路。
- **域名池**：官方下发 16+ 个同构域名（`api.08zbidl.com` / `api.46d5umpk.com` / `api.anctjd.com` / …）。爬虫默认写死 `api.bp7kprw.com`，可用 `"ext": {"host":"https://其它域名"}` 覆盖。
- **首页加载**：`homeContent` 需要 1 次分类树 + 11 次筛选项（共 12 次往返），串行约 5~6 秒。爬虫用 12 线程守护线程池并发拉取，分类树与筛选项都做静态缓存：冷启动约 2.8 秒，二次进入 2ms；`homeVideoContent` 的 3 个聚合位同样并发（约 0.6 秒）。

> **⚠️ 播放地址的「广告注入式防盗链」（最容易踩的坑）**
>
> 接口返回的 m3u8 在 CDN `vd.wmvbo.com` 上，该 CDN **不看签名、只看请求头**：
>
> | 请求特征 | 结果 |
> | --- | --- |
> | 带 `Referer`（任意值，连 `Origin` 也不行） | `302 → https://app.wanglaoshi.中国/hls2/index.m3u8`，占位片 |
> | `User-Agent` 含 `Mozilla`（WebView / X5 / 浏览器内核播放器） | 同上，302 到占位片 |
> | 非浏览器 UA（`ExoPlayerLib/*`、`Lavf/*`、`stagefright/*`、`okhttp/*`、空 UA）+ 不带 Referer | `200`，完整正片 |
>
> `m3u8` 与 `ts` 分片**同一套规则**（分片即便不带 Referer，用浏览器 UA 照样 302）。
> 实测同一部影片：浏览器 UA 拿到 20s / 2 段，`okhttp/4.9.0` 拿到 **7740s / 775 段**；
> 地址寿命 ≥30 分钟，播放过程中不会失效。
>
> **`playerContent` 下发的 `header` 必须是 JSON *字符串***（塞 `JSONObject` 会被客户端静默忽略），
> 内含 `User-Agent: okhttp/4.9.0`：Exo（反射替换 userAgent）/ IJK（`user_agent` 选项）/
> 系统内核（`setDataSource` 带 headers）三种内核实测都会用它替换自身 UA，因此**默认就返回直链 + header**。
>
> **⚠️ `playUrl` 是「前缀」不是地址**（最容易踩、也最像「未知错误」的坑）：TVBox 系客户端实际播放的是
> `playUrl + url`（见 `PlayFragment`）。曾把完整地址同时写进 `playUrl` 与 `url`，客户端拼成
> `.../index.m3u8https://vd.wmvbo.com/...`，于是先提示**「源视频文件丢失」**、后又提示**「未知错误」**。
> 正确写法：`playUrl` 留空，地址只放 `url`。
>
> **兜底通道**：`ext` 传 `{"proxy":true}` 时改走本机回环中继（`GuaZi.Relay`），
> `playerContent` 返回 `http://127.0.0.1:{随机端口}/p/{会话}/index.m3u8`，由爬虫自己按 CDN 要求
> （非浏览器 UA、零 Referer）取流，并把播放列表里的**分片与密钥地址全部改写成 `127.0.0.1`** ——
> 播放器全程只跟本机通信，它用什么 UA、加不加 Referer、发不发 `Range` 都不再影响结果。
> 中继支持 `Range/206` 直通、`HEAD`、`URI="…"` 改写、长连接关闭；端口随机绑定、线程全部为守护线程、
> 会话上限 256。用于内核/外部播放器不认 `header`、或客户端禁明文 HTTP 导致中继不可达时的对照排查。
>
> 另外：改完播放相关代码务必实拉一次 m3u8 累加 `#EXTINF`，片长 < 2 分钟就是被插了占位片。

| 功能 | 接口 |
| --- | --- |
| 分类树 | `/App/Resource/VodType/show` → 电影1 / AI漫剧74 / 连续剧2 / 综艺3 / 动漫4 / 短剧64 … |
| 筛选项 | `/App/IndexList/indexScreen?t_id={tid}` → 类型/地区/年份/排序 |
| 列表 | `/App/IndexList/indexList`（`tid,page,pageSize,sub,sort,area,year`，`sort` 取 `d_id`/`d_addtime`/`d_score`） |
| 首页 | `/App/IndexList/index?pid={6,1,2}` |
| 搜索 | `/App/Index/findMoreVod`（`keywords,order_val,search_type=1`） |
| 详情 | `/App/Resource/Vod/showOne?d_id={id}` → `vurl_clouds[]` |
| 剧集 | `/App/Resource/Vurl/show?vurl_cloud_id={线路id}&vod_d_id={id}` → 每集 `default_param` |
| 播放 | `/App/Resource/VurlDetail/showOne`（`vod_id,vurl_id,domain_type,resolution,type=play`）→ `.url` 即 m3u8 直链 |

## 八、免责声明

本项目仅供技术学习与个人使用，数据来源于目标站点公开页面，请勿用于商业用途或高频抓取。
