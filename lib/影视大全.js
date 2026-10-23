// 影视大全（原 看影视）  https://www.kanys8.com
// jar 爬虫 csp_Kanys8 的 DRPY(js) 移植版，逻辑与 Kanys8.java 一一对应
//
// 苹果CMS10 + stui（ysdqst 样式）：
//   分类  /filmlist/{slug}.html              第 N 页 /filmlist/{slug}-{N}.html
//   筛选  /filmshow/{type}-{area}---------{year}.html（12 段：0=type 1=area 11=year）
//   搜索  /filmsearch/{enc(kw)}----------{N}---.html
//   详情  /filmdetail/{id}.html
//   播放  /filmplay/{id}-{sid}-{nid}.html → player_aaaa={"url":"..m3u8"} 直链
//
// 配置示例：
// {
//     "key": "drpy_js_影视大全",
//     "name": "影视大全",
//     "type": 3,
//     "api": "./lib/drpy2.min.js",
//     "searchable": 1,
//     "quickSearch": 1,
//     "filterable": 1,
//     "ext": "./lib/影视大全.js"
// }

var HELPERS = `
function cl(t) {
    if (!t) return '';
    t = String(t).replace(/<[^>]+>/g, ' ')
        .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&#39;/g, "'");
    t = t.replace(/\\s+/g, ' ').trim();
    return t.replace(/^[：:，,、·]+/, '').replace(/[：:，,、·]+$/, '').trim();
}
function fst(text, re) {
    if (!text) return '';
    try {
        let m = text.match(new RegExp(re));
        return m && m[1] !== undefined ? m[1] : '';
    } catch (e) {
        return '';
    }
}
function last1(text, re) {
    if (!text) return '';
    let out = '';
    try {
        let r = new RegExp(re, 'g');
        let m = null;
        while ((m = r.exec(text)) !== null) out = m[1];
    } catch (e) {}
    return out;
}
function mall(text, re) {
    let out = [];
    try {
        let r = new RegExp(re, 'g');
        let m = null;
        while ((m = r.exec(text)) !== null) out.push(m);
    } catch (e) {}
    return out;
}
function isV(u) {
    if (!u) return false;
    return /\\.(m3u8|mp4|mkv|avi|flv|m4a)/i.test(u);
}
function absUrl(u) {
    if (!u) return '';
    u = String(u).trim();
    if (u.slice(0, 4) === 'http') return u;
    if (u.slice(0, 2) === '//') return 'https:' + u;
    if (u.slice(0, 1) === '/') return HOST + u;
    return u;
}
// 详情页「类型/地区/年份/主演/导演」取值：label 在 <span> 里、值紧跟其后。
// 多个字段可能挤在同一个 <p> 内，故在下一个「xx：</span>」处截断。
function meta(html, label) {
    let r = new RegExp(label + '[：:]?</span>([\\\\s\\\\S]{0,2000}?)</p>');
    let m = html.match(r);
    if (!m) {
        r = new RegExp(label + '[：:]?</span>([\\\\s\\\\S]{0,2000}?)</div>');
        m = html.match(r);
    }
    if (!m) return '';
    let v = m[1];
    let p = v.search(new RegExp('[^：\\\\s]{1,6}[：:]</span>'));
    if (p >= 0) v = v.slice(0, p);
    // 截断可能切在下一个标签中间，再切掉尾部半截标签
    let lt = v.lastIndexOf('<');
    if (lt >= 0) v = v.slice(0, lt);
    return cl(v);
}
`;

// 列表项：<a class=".. lazyload" href="/filmdetail/{id}.html" title="片名" data-original="..">
//          <span class="..">高清</span></a>
// 注意：该站的 class 名会随机化（实测 pUi75J/lazyload 等），只能按 href + 属性取值
var PARSER = `
function parseList(html) {
    let videos = [];
    let seen = {};
    let items = mall(html, '<a [^>]*href="/filmdetail/(\\\\d+)\\\\.html"[^>]*>([\\\\s\\\\S]{0,400}?)</a>');
    for (let i = 0; i < items.length; i++) {
        let tag = items[i][0];
        let id = items[i][1];
        let body = items[i][2] || '';
        if (!id || seen[id]) continue;
        seen[id] = 1;
        let name = (fst(tag, 'title="([^"]*)"') || '').trim();
        if (!name) continue;
        let pic = fst(tag, 'data-original="([^"]+)"');
        if (!pic) pic = fst(tag, 'src="([^"]+)"');
        if (!pic) pic = fst(tag, 'background-image:\\\\s*url\\\\(([^)]+)\\\\)');
        // 备注：卡片 <a> 内最后一个非空 <span> 文本；拿不到再向后扫 400 字符
        let remarks = '';
        let sp = mall(body, '<span[^>]*>([^<]+)</span>');
        for (let k = 0; k < sp.length; k++) {
            let t = (sp[k][1] || '').trim();
            if (t) remarks = t;
        }
        if (!remarks) {
            let pos = html.indexOf(tag);
            let tail = html.slice(pos + tag.length, pos + tag.length + 400);
            remarks = fst(tail, '<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]+)<');
            if (!remarks) remarks = fst(tail, '<span[^>]*class="[^"]*score[^"]*"[^>]*>([^<]+)<');
        }
        videos.push({
            vod_id: HOST + '/filmdetail/' + id + '.html',
            vod_name: name,
            vod_pic: absUrl(pic),
            vod_remarks: (remarks || '').trim()
        });
    }
    return videos;
}
`;

var rule = {
    title: '影视大全',
    host: 'https://www.kanys8.com',
    homeUrl: '/',
    url: '/filmlist/fyclass-fypage.html[/filmlist/fyclass.html]',
    searchUrl: '/filmsearch/**----------fypage---.html',
    searchable: 1,
    quickSearch: 1,
    filterable: 1,
    timeout: 10000,
    limit: 90,
    double: false,
    play_parse: true,
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': 'https://www.kanys8.com/'
    },
    class_parse: $js.toString(() => {
        input = [
            { type_id: 'dy', type_name: '电影' },
            { type_id: 'dsj', type_name: '电视剧' },
            { type_id: 'dm', type_name: '动漫' }
        ];
        let types = {
            'dy': [['全部', 'dy'], ['剧情片', 'jqp'], ['喜剧片', 'xjp'], ['动作片', 'dzp'],
                   ['爱情片', 'aqp'], ['科幻片', 'khp'], ['悬疑片', 'xyp'], ['恐怖片', 'kbp'],
                   ['犯罪片', 'fzp'], ['战争片', 'zzp'], ['动画片', 'dhp'], ['奇幻片', 'qhp'],
                   ['冒险片', 'mxp'], ['惊悚片', 'jsp'], ['传记片', 'cjp'], ['历史片', 'lsp'],
                   ['纪录片', 'jlp'], ['音乐片', 'ylp'], ['歌舞片', 'gwp'], ['武侠片', 'wxp'],
                   ['古装片', 'gzp'], ['邵氏电影', 'ssdy']],
            'dsj': [['全部', 'dsj'], ['国产剧', 'guocj'], ['港台剧', 'gangtj'], ['欧美剧', 'oumj'],
                    ['日韩剧', 'rihj'], ['海外剧', 'haiwj']],
            'dm': [['全部', 'dm'], ['国产动漫', 'gcdm'], ['日本动漫', 'rbdm'],
                   ['欧美动漫', 'omdm'], ['海外动漫', 'hwdm']]
        };
        let area = ['大陆', '香港', '台湾', '美国', '法国', '英国', '日本', '韩国',
                    '泰国', '德国', '丹麦', '印度', '意大利', '西班牙', '其它'];
        let year = ['2026', '2025', '2024', '2023', '2022', '2021', '2020', '2019',
                    '2018', '2017', '2016', '2015', '2014', '2013', '2012', '更早'];
        let f = {};
        Object.keys(types).forEach(function (tid) {
            f[tid] = [
                { key: 'type', name: '类型', value: types[tid].map(function (it) {
                    return { n: it[0], v: it[1] };
                }) },
                { key: 'area', name: '地区', value: [{ n: '全部', v: '' }].concat(area.map(function (a) {
                    return { n: a, v: a };
                })) },
                { key: 'year', name: '年份', value: [{ n: '全部', v: '' }].concat(year.map(function (y) {
                    return { n: y, v: y };
                })) }
            ];
        });
        homeObj.filter = f;
    }),
    推荐: `js:${HELPERS}${PARSER}
        let html = request(HOST + '/');
        VODS = parseList(html);
    `,
    一级: `js:${HELPERS}${PARSER}
        let t = MY_CATE;
        let pg = MY_PAGE;
        let fl = MY_FL || {};
        let type = (fl.type || '').trim() || t;
        let area = (fl.area || '').trim();
        let year = (fl.year || '').trim();
        let url = '';
        if (!area && !year) {
            url = HOST + '/filmlist/' + type + (pg > 1 ? '-' + pg : '') + '.html';
        } else {
            let parts = ['', '', '', '', '', '', '', '', '', '', '', ''];
            parts[0] = type;
            parts[1] = area;
            parts[11] = year;
            url = HOST + '/filmshow/' + parts.join('-') + '.html';
        }
        let html = request(url);
        VODS = parseList(html);
    `,
    搜索: `js:${HELPERS}${PARSER}
        let kw = KEY || '';
        let dashes = '';
        for (let i = 0; i < 10; i++) dashes += '-';
        let url = HOST + '/filmsearch/' + urlencode(kw) + dashes + MY_PAGE + '---.html';
        let html = request(url);
        VODS = parseList(html);
    `,
    二级: `js:${HELPERS}
        let html = request(input);
        let id = fst(input, '/filmdetail/(\\\\d+)\\\\.html');

        // 站点换过皮：stui 的 class 名被随机化，故一律按标签 + 关键词取值
        let name = cl(fst(html, '<h1[^>]*class="title"[^>]*>([^<]+)'));
        if (!name) name = cl(fst(html, '<h1[^>]*>([^<]+)</h1>'));
        if (!name) {
            let t = cl(fst(html, '<title>([^<]+)</title>'));
            let p = t.indexOf('_');
            if (p > 0) t = t.slice(0, p);
            name = t.trim();
        }
        if (!name) name = id;

        let year = meta(html, '年份');
        if (!year) year = fst(html, '<span[^>]*>(\\\\d{4})</span>');

        let pic = fst(html, 'class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)"');
        if (!pic) pic = fst(html, 'data-original="([^"]+\\\\.(?:jpg|jpeg|png|webp))"');
        if (!pic) pic = fst(html, 'background-image:\\\\s*url\\\\(([^)]+\\\\.(?:jpg|jpeg|png|webp))\\\\)');

        let typeName = meta(html, '类型');
        let area = meta(html, '地区');
        let actor = meta(html, '主演');
        let director = meta(html, '导演');
        let remarks = meta(html, '更新');

        // 内容：紧跟「简介：</span>」之后的那段 <span>（取最长的一段）
        let content = '';
        let ci = html.indexOf('简介：</span>');
        if (ci < 0) ci = html.indexOf('简介:</span>');
        if (ci >= 0) {
            let sp = mall(html.slice(ci, ci + 3000), '<span[^>]*>([\\\\s\\\\S]*?)</span>');
            for (let i = 0; i < sp.length; i++) {
                let t = cl(sp[i][1]);
                if (t.length > content.length) content = t;
            }
        }
        if (!content) content = cl(fst(html, '<span class="detail-content"[^>]*>([\\\\s\\\\S]*?)</span>'));
        if (!content) content = cl(fst(html, '<div class="detail"[^>]*>([\\\\s\\\\S]*?)</div>'));

        // 源名不紧邻 ul：取含 /filmplay/ 的 <ul> 块，向前回溯 800 字符内最近的 <h3>/<h4>
        let froms = [];
        let urls = [];
        let auto = 0;
        let blocks = mall(html, '<ul[^>]*>([\\\\s\\\\S]*?)</ul>');
        for (let i = 0; i < blocks.length; i++) {
            let eps = mall(blocks[i][1], '<a[^>]+href="(/filmplay/\\\\d+-\\\\d+-\\\\d+\\\\.html)"[^>]*>([^<]*)</a>');
            if (eps.length === 0) continue;
            let pos = html.indexOf(blocks[i][0]);
            let before = html.slice(Math.max(0, pos - 800), pos);
            let src = last1(before, '<h3[^>]*>\\\\s*([^<]{1,40}?)\\\\s*</h3>');
            if (!src) src = last1(before, '<h4[^>]*>\\\\s*([^<]{1,40}?)\\\\s*</h4>');
            if (!src) src = '线路' + (++auto);
            let list = [];
            for (let k = 0; k < eps.length; k++) {
                let label = (eps[k][2] || '').trim() || ('第' + (k + 1) + '集');
                list.push(label + '$' + eps[k][1]);
            }
            froms.push(src);
            urls.push(list.join('#'));
        }

        VOD = {
            vod_id: input,
            vod_name: name,
            vod_pic: absUrl(pic),
            type_name: typeName,
            vod_year: year,
            vod_area: area,
            vod_actor: actor,
            vod_director: director,
            vod_remarks: remarks,
            vod_content: content,
            vod_play_from: froms.length ? froms.join('$$$') : '暂无资源',
            vod_play_url: urls.join('$$$')
        };
    `,
    // /filmplay/{id}-{sid}-{nid}.html → player_aaaa.url 已是直链 m3u8（无二跳）
    lazy: `js:${HELPERS}
        let url = input;
        if (!isV(url)) {
            let html = request(absUrl(url));
            let p = fst(html, 'player_\\\\w+\\\\s*=\\\\s*\\\\{[\\\\s\\\\S]*?"url"\\\\s*:\\\\s*"([^"]+)"');
            if (!p) p = fst(html, '"url"\\\\s*:\\\\s*"([^"]+\\\\.(?:m3u8|mp4)[^"]*)"');
            if (p) url = p.split('\\\\/').join('/');
        }
        if (isV(url)) {
            input = { parse: 0, jx: 0, url: url, js: '' };
        } else {
            input = { parse: 1, jx: 0, url: absUrl(input), js: '' };
        }
    `,
};
