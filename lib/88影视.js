// 88影视  https://www.88ystv.com
// jar 爬虫 csp_Movietv88 的 DRPY(js) 移植版，逻辑与 Movietv88.java 一一对应
//
// pc88ysw 自制模板：
//   分类  /vod-type-id-{tid}-pg-{P}.html
//   筛选  /vod-search-pg-{P}-wd--area-{A}-by-{B}-typeid-{T}-year-{Y}.html
//   搜索  /vod-search-pg-{P}-wd-{enc(wd)}.html
//   详情  /{slug}/{yyyyMM}/{id}.html
//   分集  /vod-play-id-{id}-src-{S}-num-{N}.html  → 播放页交给客户端 parse=1 嗅探
//
// 配置示例：
// {
//     "key": "drpy_js_88影视",
//     "name": "88影视",
//     "type": 3,
//     "api": "./lib/drpy2.min.js",
//     "searchable": 1,
//     "quickSearch": 1,
//     "filterable": 1,
//     "ext": "./lib/88影视.js"
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
`;

// 列表解析（首页 / 分类 / 搜索 共用）
// <li class="p1 m1"><a href="/{slug}/{yyyyMM}/{id}.html" title="片名">
//   <img data-original=".."><span class="lzbz"><p class="name">..</p><p class="actor">..</p>..</span>
//   <p class="other"><i>备注</i></p></a></li>
var PARSER = `
function parseList(html) {
    let videos = [];
    let seen = {};
    let items = mall(html, '<li class="p1 m1">\\\\s*<a href="(/[^"]+\\\\.html)" title="([^"]*)"[^>]*>([\\\\s\\\\S]*?)</a>\\\\s*</li>');
    for (let i = 0; i < items.length; i++) {
        let href = items[i][1];
        let name = (items[i][2] || '').trim();
        let inner = items[i][3] || '';
        if (!name) continue;
        let id = fst(href, '/(\\\\d+)\\\\.html$');
        if (!id || seen[id]) continue;
        seen[id] = 1;
        let pic = fst(inner, 'data-original="([^"]+)"');
        if (!pic) pic = fst(inner, '<img[^>]+src="([^"]+)"');
        let remarks = fst(inner, '<p class="other">[\\\\s\\\\S]*?<i>([^<]+)</i>');
        let typeName = '';
        let yearArea = '';
        let lz = fst(inner, '<span class="lzbz"[^>]*>([\\\\s\\\\S]*?)</span>');
        if (lz) {
            let acts = mall(lz, '<p class="actor"[^>]*>([^<]*)</p>');
            let lines = [];
            for (let k = 0; k < acts.length; k++) lines.push((acts[k][1] || '').trim());
            if (lines.length >= 2) typeName = lines[1];
            if (lines.length >= 3) yearArea = lines[2];
        }
        let vod = {
            vod_id: absUrl(href),
            vod_name: name,
            vod_pic: absUrl(pic),
            vod_remarks: cl(remarks)
        };
        if (typeName) vod.type_name = cl(typeName);
        if (yearArea) {
            let p = yearArea.indexOf('/');
            if (p > 0) {
                let y = yearArea.slice(0, p).trim();
                let a = yearArea.slice(p + 1).trim();
                if (/^\\d{4}$/.test(y)) vod.vod_year = y;
                vod.vod_area = a;
            }
        }
        videos.push(vod);
    }
    return videos;
}
`;

var rule = {
    title: '88影视',
    host: 'https://www.88ystv.com',
    homeUrl: '/',
    url: '/vod-type-id-fyclass-pg-fypage.html',
    searchUrl: '/vod-search-pg-fypage-wd-**.html',
    searchable: 1,
    quickSearch: 1,
    filterable: 1,
    timeout: 10000,
    limit: 90,
    double: false,
    play_parse: true,
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': 'https://www.88ystv.com/'
    },
    class_parse: $js.toString(() => {
        input = [
            { type_id: '1', type_name: '电影' },
            { type_id: '2', type_name: '电视剧' },
            { type_id: '3', type_name: '综艺' },
            { type_id: '4', type_name: '动漫' }
        ];
        let subs = {
            '1': [['1', '全部'], ['5', '动作片'], ['6', '喜剧片'], ['7', '爱情片'], ['8', '科幻片'],
                  ['9', '恐怖片'], ['10', '剧情片'], ['11', '战争片'], ['16', '纪录片'],
                  ['17', '动画片'], ['18', '悬疑片'], ['19', '犯罪片'], ['20', '奇幻片'],
                  ['25', '邵氏电影'], ['26', '魔幻片'], ['27', '其他片']],
            '2': [['2', '全部'], ['12', '国产剧'], ['13', '港台剧'], ['14', '日韩剧'],
                  ['15', '欧美剧'], ['29', '其他剧']],
            '3': [['3', '全部'], ['23', '内地综艺'], ['24', '港台综艺'], ['21', '日韩综艺'], ['22', '欧美综艺']],
            '4': [['4', '全部'], ['30', '国产动漫'], ['31', '日本动漫'], ['32', '欧美动漫'], ['33', '其他动漫']]
        };
        let area = ['大陆', '香港', '台湾', '美国', '法国', '英国', '日本', '韩国', '泰国',
                    '德国', '丹麦', '印度', '意大利', '西班牙', '新加坡', '马来西亚', '俄罗斯', '其它'];
        let year = ['2026', '2025', '2024', '2023', '2022', '2021', '2020', '2019', '2018',
                    '2017', '2016', '2015', '2014', '2013', '2012', '2011', '2010', '2009',
                    '2008', '2007', '2006', '2005', '2004', '2003', '2002', '2001', '2000',
                    '1999', '1998', '1997', '1996', '1995', '更早'];
        let f = {};
        Object.keys(subs).forEach(function (tid) {
            let sub = subs[tid].map(function (it) {
                return { n: it[1], v: it[0] };
            });
            f[tid] = [
                { key: 'typeid', name: '类型', value: sub },
                { key: 'area', name: '地区', value: [{ n: '全部', v: '' }].concat(area.map(function (a) {
                    return { n: a, v: a };
                })) },
                { key: 'year', name: '年份', value: [{ n: '全部', v: '' }].concat(year.map(function (y) {
                    return { n: y, v: y };
                })) },
                { key: 'by', name: '排序', value: [
                    { n: '最新', v: '' }, { n: '最新', v: 'time' },
                    { n: '人气', v: 'hits' }, { n: '评分', v: 'score' }
                ] }
            ];
        });
        homeObj.filter = f;
    }),
    推荐: `js:${HELPERS}${PARSER}
        let html = request(HOST + '/');
        VODS = parseList(html);
    `,
    一级: `js:${HELPERS}${PARSER}
        let tid = MY_CATE;
        let pg = MY_PAGE;
        let fl = MY_FL || {};
        let typeid = (fl.typeid || '').trim();
        let area = (fl.area || '').trim();
        let year = (fl.year || '').trim();
        let by = (fl.by || '').trim();
        if (typeid === tid) typeid = '';
        let url = '';
        if (!area && !year && !by && !typeid) {
            url = HOST + '/vod-type-id-' + tid + '-pg-' + pg + '.html';
        } else {
            url = HOST + '/vod-search-pg-' + pg + '-wd-';
            if (area) url += '-area-' + urlencode(area);
            if (by) url += '-by-' + by;
            if (typeid) url += '-typeid-' + typeid;
            if (year) url += '-year-' + year;
            url += '.html';
        }
        let html = request(url);
        VODS = parseList(html);
    `,
    搜索: `js:${HELPERS}${PARSER}
        let kw = KEY || '';
        let url = HOST + '/vod-search-pg-' + MY_PAGE + '-wd-' + urlencode(kw) + '.html';
        let html = request(url);
        VODS = parseList(html);
    `,
    二级: `js:${HELPERS}
        let html = request(input);
        let raw = input;

        let name = cl(fst(html, '<h1>([^<]+)'));
        if (!name) name = cl(fst(html, '<h1[^>]*>([^<]+)'));

        let pic = fst(html, 'data-original="([^"]+\\\\.(?:jpg|jpeg|png|webp))"');
        if (!pic) pic = fst(html, '<img[^>]+src="([^"]+\\\\.(?:jpg|jpeg|png|webp))"');

        let content = cl(fst(html, '<span class="detail-content"[^>]*>([\\\\s\\\\S]*?)</span>'));
        if (!content) content = cl(fst(html, '<div class="detail"[^>]*>([\\\\s\\\\S]*?)</div>'));
        if (!content) content = cl(fst(html, '<div[^>]*class="[^"]*des[^"]*"[^>]*>([\\\\s\\\\S]*?)</div>'));

        let data = {};
        let rows = mall(html, '<p class="data[^"]*"[^>]*>\\\\s*<span[^>]*>([^：:<]+)</span>([\\\\s\\\\S]*?)</p>');
        for (let i = 0; i < rows.length; i++) {
            let k = (rows[i][1] || '').trim();
            let v = cl(rows[i][2]);
            if (k && v && !data[k]) data[k] = v;
        }

        // 源名：li#tab8N → 线路 N
        let srcMap = {};
        let tabs = mall(html, '<li[^>]*id="tab8(\\\\d)"[^>]*>([\\\\s\\\\S]*?)</li>');
        for (let i = 0; i < tabs.length; i++) {
            let s = parseInt(tabs[i][1], 10);
            let n = cl(tabs[i][2]);
            if (n) srcMap[s] = n;
        }

        let froms = [];
        let urls = [];
        for (let src = 1; src <= 9; src++) {
            let sIdx = html.indexOf('id="stab8' + src + '"');
            if (sIdx < 0) continue;
            let eIdx = html.indexOf('id="stab8', sIdx + 1);
            if (eIdx < 0) {
                eIdx = html.length;
            } else {
                let divStart = html.lastIndexOf('<div', sIdx + 1);
                if (divStart > sIdx) eIdx = divStart;
            }
            let block = html.slice(sIdx, eIdx);
            let eps = mall(block, '<a href="(/vod-play-id-\\\\d+-src-\\\\d+-num-\\\\d+\\\\.html)"[^>]*title="([^"]*)"[^>]*>');
            let list = [];
            for (let i = 0; i < eps.length; i++) {
                let label = (eps[i][2] || '').trim() || ('第' + (i + 1) + '集');
                list.push(label + '$' + eps[i][1]);
            }
            if (list.length === 0) continue;
            froms.push(srcMap[src] || ('线路' + src));
            urls.push(list.join('#'));
        }

        let id = raw;
        let m = raw.match(/\\/(\\d+)\\.html/);
        if (m) id = m[1];

        VOD = {
            vod_id: input,
            vod_name: name || id,
            vod_pic: absUrl(pic),
            type_name: data['类型'] || '',
            vod_year: data['年份'] || '',
            vod_area: data['地区'] || '',
            vod_actor: data['主演'] || '',
            vod_director: data['导演'] || '',
            vod_remarks: data['更新'] || '',
            vod_content: content,
            vod_play_from: froms.length ? froms.join('$$$') : '暂无资源',
            vod_play_url: urls.join('$$$')
        };
    `,
    // 播放页内嵌 var mac_url=unescape('%uXXXX..$token$from') 需执行站点 JS 才能拿到直链，
    // 与 jar 版一致：直接把播放页交给客户端嗅探（parse=1）
    lazy: `js:${HELPERS}
        let u = absUrl(input);
        input = { parse: 1, jx: 0, url: u, js: '' };
    `,
};
