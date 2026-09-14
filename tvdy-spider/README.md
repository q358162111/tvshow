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
├─ src/com/github/catvod/spider/TvDy.java   爬虫主体（唯一需要改的文件）
├─ src-alias/.../Tvdy.java                  大小写别名类（独立目录，规避 Windows 文件系统大小写不敏感）
├─ stubs/                                   编译期桩类，不会打进 jar
├─ build.ps1                                一键编译打包（javac → d8 → jar）
├─ dist/TvDy.jar                            构建产物
└─ build/                                   中间产物（已 gitignore）
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

## 七、免责声明

本项目仅供技术学习与个人使用，数据来源于目标站点公开页面，请勿用于商业用途或高频抓取。
