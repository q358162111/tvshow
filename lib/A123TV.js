// A123TV  https://a123tv.com
// jar 爬虫 csp_A123tv 的 DRPY(js) 移植版，逻辑与 A123tv.java 一一对应
//
// 自制 w4 模板（苹果CMS 数据源，路由全部 slug）：
//   分类  /t/{typeId}.html            第 P 页 /t/{typeId}/p{P}.html
//   搜索  /s/{enc(kw)}.html           第 P 页 /s/{enc(kw)}/p{P}.html
//   详情  /v/{slug}.html
//   分集  /v/{slug}/{ttl}z{n}.html    ← n 从 0 起，由线路元数据直接算出
//   播放  分集页 div.w4-player[data-src] 即 m3u8 直链（CDN 无防盗链）
//
// 两个必须照抄的实测结论：
//   1. 线路项自带 data-ttl 与「共N集」，第 n 集就是 /v/{slug}/{ttl}z{n}.html；
//      越界下标不会 404 而是静默回落到第 1 集，所以必须严格按「共N集」生成。
//   2. 分类页分页控件是固定 13 格的滑动窗口，总页数拿不到 → 不设 pagecount，交给客户端逐页探。
//
// 配置示例：
// {
//     "key": "drpy_js_A123TV",
//     "name": "A123TV",
//     "type": 3,
//     "api": "./lib/drpy2.min.js",
//     "searchable": 1,
//     "quickSearch": 1,
//     "filterable": 1,
//     "ext": "./lib/A123TV.js"
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
function absUrl(u) {
    if (!u) return '';
    u = String(u).trim();
    if (u.slice(0, 4) === 'http') return u;
    if (u.slice(0, 2) === '//') return 'https:' + u;
    if (u.slice(0, 1) === '/') return HOST + u;
    return u;
}
// vod_id 可能是 slug / slug.html / /v/slug.html / 完整 URL，统一成 slug
function toSlug(s) {
    let t = (s || '').trim();
    if (!t) return '';
    if (t.slice(0, 4) === 'http') t = t.replace(/^https?:\\/\\/[^/]+/, '');
    t = t.replace(/^\\/v\\//, '');
    if (/\\.html$/.test(t)) t = t.slice(0, t.length - 5);
    let p = t.indexOf('/');
    if (p >= 0) t = t.slice(0, p);
    return t;
}
`;

// <a class="w4-item" href="/v/{slug}.html">
//   <figure class="w4-item-cover"><img data-src="..jpg" alt="片名">
//     <div class="r">1080p</div><div class="s"><i></i><span>97个线路</span></div></figure>
//   <div class="w4-item-info"><div class="t" title="片名">片名</div>
//     <div class="i">剧情片 / 2026年</div></div></a>
var PARSER = `
function parseList(html) {
    let videos = [];
    let seen = {};
    let items = mall(html, '<a class="w4-item" href="/v/([^"/]+)\\\\.html">([\\\\s\\\\S]{0,1000}?)</a>');
    for (let i = 0; i < items.length; i++) {
        let slug = items[i][1];
        if (!slug || seen[slug]) continue;
        seen[slug] = 1;
        let body = items[i][2] || '';
        let name = (fst(body, '<div class="t"[^>]*title="([^"]*)"') || '').trim();
        if (!name) name = cl(fst(body, '<div class="t"[^>]*>([^<]*)</div>'));
        if (!name) name = (fst(body, 'alt="([^"]*)"') || '').trim();
        if (!name) continue;
        let pic = fst(body, 'data-src="([^"]+)"');
        let remarks = (fst(body, '<div class="r">([^<]*)</div>') || '').trim();
        if (!remarks) remarks = (fst(body, '<span>(\\\\d+个线路)</span>') || '').trim();
        videos.push({
            vod_id: HOST + '/v/' + slug + '.html',
            vod_name: name,
            vod_pic: absUrl(pic),
            vod_remarks: remarks
        });
    }
    return videos;
}
`;

var rule = {
    title: 'A123TV',
    host: 'https://a123tv.com',
    homeUrl: '/',
    url: '/t/fyclass.html[/t/fyclass.html]',
    searchUrl: '/s/**.html',
    searchable: 1,
    quickSearch: 1,
    filterable: 1,
    timeout: 10000,
    limit: 36,
    double: false,
    play_parse: true,
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': 'https://a123tv.com/'
    },
    class_parse: $js.toString(() => {
        input = [
            { type_id: '10', type_name: '电影' },
            { type_id: '11', type_name: '连续剧' },
            { type_id: '12', type_name: '综艺' },
            { type_id: '13', type_name: '动漫' }
        ];
        let subs = {
            '10': [['1001', '动作片'], ['1002', '喜剧片'], ['1003', '爱情片'], ['1004', '科幻片'],
                   ['1005', '恐怖片'], ['1006', '剧情片'], ['1007', '战争片'], ['1008', '纪录片'],
                   ['1010', '动漫电影'], ['1011', '奇幻片'], ['1013', '动画片'], ['1014', '犯罪片'],
                   ['1016', '悬疑片'], ['1019', '邵氏电影'], ['1022', '歌舞片'], ['1024', '家庭片'],
                   ['1025', '古装片'], ['1026', '历史片'], ['1027', '4K电影']],
            '11': [['1101', '国产剧'], ['1102', '香港剧'], ['1103', '韩国剧'], ['1104', '欧美剧'],
                   ['1105', '台湾剧'], ['1106', '日本剧'], ['1107', '海外剧'], ['1108', '泰国剧'],
                   ['1110', '港台剧'], ['1111', '日韩剧']],
            '12': [['1201', '内地综艺'], ['1202', '港台综艺'], ['1203', '日韩综艺'],
                   ['1204', '欧美综艺'], ['1205', '国外综艺']],
            '13': [['1301', '国产动漫'], ['1302', '日韩动漫'], ['1303', '欧美动漫'], ['1305', '海外动漫']]
        };
        let f = {};
        Object.keys(subs).forEach(function (tid) {
            f[tid] = [{ key: 'class', name: '类型', value: [{ n: '全部', v: '' }].concat(subs[tid].map(function (it) {
                return { n: it[1], v: it[0] };
            })) }];
        });
        homeObj.filter = f;
    }),
    推荐: `js:${HELPERS}${PARSER}
        let html = request(HOST + '/');
        VODS = parseList(html);
    `,
    一级: `js:${HELPERS}${PARSER}
        let fl = MY_FL || {};
        let cls = (fl.class || '').trim();
        let typeId = cls || MY_CATE;
        let pg = MY_PAGE;
        let url = HOST + '/t/' + typeId + (pg > 1 ? '/p' + pg : '') + '.html';
        let html = request(url);
        VODS = parseList(html);
    `,
    搜索: `js:${HELPERS}${PARSER}
        let pg = MY_PAGE;
        let url = HOST + '/s/' + urlencode(KEY || '') + (pg > 1 ? '/p' + pg : '') + '.html';
        let html = request(url);
        VODS = parseList(html);
    `,
    二级: `js:${HELPERS}
        let html = request(input);
        let slug = toSlug(input);
        let maxLines = 12;

        let name = cl(fst(html, '<li class="on">\\\\s*<h1>([^<]*)</h1>'));
        if (!name) name = slug;

        // 类型必须限定在面包屑里取：页面顶部导航也有一堆 /t/ 链接
        let type = '';
        let bread = fst(html, '<div class="w4-bread">([\\\\s\\\\S]*?)</div>');
        if (bread) type = last1(bread, '<a href="/t/\\\\d+\\\\.html">([^<]+)</a>');

        let pic = absUrl(fst(html, '<div[^>]*class="w4-player"[^>]*data-poster="([^"]*)"'));
        if (!pic) pic = absUrl(fst(html, 'data-poster="([^"]*)"'));

        // 详情页只渲染当前选中线路的分集，标题是真实集名
        let onCode = fst(html, 'class="w4-line-item on"[^>]*href="/v/[^"]*/([^/"]+)\\\\.html"');
        let onTitles = [];
        let blk = fst(html, '<div[^>]*class="w4-episode-list[^"]*"[^>]*>([\\\\s\\\\S]*?)</div>');
        if (blk) {
            let am = mall(blk, '<a[^>]*>([^<]*)</a>');
            for (let i = 0; i < am.length; i++) {
                let t = (am[i][1] || '').trim();
                if (t) onTitles.push(t);
            }
        }

        let froms = [];
        let urls = [];
        let seen = {};
        let year = '';
        let remarks = '';
        let lineNo = 0;

        let lines = mall(html, '<a class="w4-line-item[^"]*" href="(/v/[^"]+?)"[^>]*title="([^"]*)">([\\\\s\\\\S]{0,1200}?)</a>');
        for (let i = 0; i < lines.length; i++) {
            let href = lines[i][1];
            let lineName = (lines[i][2] || '').trim();
            let body = lines[i][3] || '';

            let ttl = fst(body, 'data-ttl="([^"]+)"');
            if (!ttl) ttl = fst(href, '/([^/"]+?)z\\\\d+\\\\.html');
            if (!ttl || seen[ttl]) continue;
            seen[ttl] = 1;

            let cnt = parseInt(fst(body, '共(\\\\d+)集'), 10);
            if (!cnt || cnt < 1) cnt = 1;
            let quality = fst(body, '<div class="i">[^<]*?/\\\\s*([0-9]{3,4}[pPkK])');
            let alt = fst(body, 'alt="([^"]*)"');
            if (!alt) alt = fst(body, '<h3 class="t">([^<]*)</h3>');

            // 片名 [1994][HD] → 年份 + 备注（取第一处 4 位年份与最后一个非年份方括号）
            if (!year) year = fst(alt, '\\\\[(\\\\d{4})\\\\]');
            if (!remarks) {
                let bm = mall(alt, '\\\\[([^\\\\[\\\\]]+)\\\\]');
                for (let k = 0; k < bm.length; k++) {
                    let v = (bm[k][1] || '').trim();
                    if (!/^\\d{4}$/.test(v)) remarks = v;
                }
                if (!remarks) remarks = quality;
            }

            if (froms.length >= maxLines) break;
            lineNo++;

            let titles = null;
            if (onCode && onCode.indexOf(ttl) === 0 && onTitles.length === cnt) titles = onTitles;

            let list = [];
            for (let n = 0; n < cnt; n++) {
                let label;
                if (titles) {
                    label = titles[n];
                } else if (cnt === 1) {
                    label = '正片';
                } else {
                    label = '第' + (cnt < 100 && n < 9 ? '0' : '') + (n + 1) + '集';
                }
                list.push(label + '$/v/' + slug + '/' + ttl + 'z' + n + '.html');
            }

            let flag = lineName || ('线路' + lineNo);
            if (quality) flag = flag + '·' + quality;
            froms.push(flag);
            urls.push(list.join('#'));
        }

        VOD = {
            vod_id: input,
            vod_name: name,
            vod_pic: pic,
            type_name: type,
            vod_year: year,
            vod_area: '',
            vod_actor: '',
            vod_director: '',
            vod_remarks: remarks,
            vod_content: '',
            vod_play_from: froms.length ? froms.join('$$$') : '暂无资源',
            vod_play_url: urls.join('$$$')
        };
    `,
    // 分集页里 div.w4-player 直接带 data-src="https://.../index.m3u8"，一次请求即可
    lazy: `js:${HELPERS}
        let html = request(absUrl(input));
        let src = fst(html, '<div[^>]*class="w4-player"[\\\\s\\\\S]{0,300}?data-src="([^"]+)"');
        if (!src) src = fst(html, 'data-src="(https?://[^"]+)"');
        if (src) {
            input = { parse: 0, jx: 0, url: src.split('\\\\/').join('/'), js: '' };
        } else {
            input = { parse: 1, jx: 0, url: absUrl(input), js: '' };
        }
    `,
};
