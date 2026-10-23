// 奈飞工厂  https://netflixgc.org
// jar 爬虫 csp_NetflixGc 的 DRPY(js) 移植版，逻辑与 NetflixGc.java 一一对应
//
// 苹果CMS10 + dsn2（"短视"主题）：
//   列表  POST /index.php/ds_api/vod  → JSON {pagecount, list[]}（分类页只是壳）
//   搜索  /vodsearch/------------{page}--1---.html?wd={enc(kw)}
//   详情  /voddetail/{id}.html
//   播放  /vodplay/{id}-{sid}-{nid}.html → player_aaaa={"url":"base64(url编码的 m3u8)"}
//
// 配置示例：
// {
//     "key": "drpy_js_奈飞工厂",
//     "name": "奈飞工厂",
//     "type": 3,
//     "api": "./lib/drpy2.min.js",
//     "searchable": 1,
//     "quickSearch": 1,
//     "filterable": 1,
//     "ext": "./lib/奈飞工厂.js"
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
// 站点偶发返回被 JSON 转义过的 HTML（\\/ \\"），统一还原
function esc(s) {
    if (!s) return '';
    return s.split('\\\\/').join('/').split('\\\\"').join('"');
}
function b64d(s) {
    let t = (s || '').trim();
    let pad = t.length % 4;
    if (pad) {
        for (let i = 0; i < 4 - pad; i++) t += '=';
    }
    try {
        return base64Decode(t) || '';
    } catch (e) {
        return '';
    }
}
function urld(s) {
    try {
        return decodeURIComponent(s);
    } catch (e) {
        return '';
    }
}
// player_aaaa.url = base64(URL 编码后的真实地址)，也可能是明文
function decodePlay(raw) {
    let s = esc(raw).trim();
    if (!s) return '';
    if (s.slice(0, 4) === 'http') return s;
    let b = b64d(s);
    if (b) {
        if (b.slice(0, 4) === 'http') return b;
        let u = urld(b);
        if (u.slice(0, 4) === 'http') return u;
    }
    let u2 = urld(s);
    if (u2.slice(0, 4) === 'http') return u2;
    return '';
}
function formBody(tid, fl, pg) {
    let by = (fl.by || '').trim() || 'time';
    let kv = [['type', tid], ['class', ''], ['area', (fl.area || '').trim()],
              ['year', (fl.year || '').trim()], ['lang', ''], ['version', ''],
              ['state', ''], ['letter', ''], ['time', ''], ['level', '0'],
              ['weekday', ''], ['by', by], ['page', String(pg)]];
    let parts = [];
    for (let i = 0; i < kv.length; i++) parts.push(kv[i][0] + '=' + urlencode(kv[i][1]));
    return parts.join('&');
}
function postVod(tid, fl, pg) {
    let fp = JSON.parse(JSON.stringify(fetch_params));
    fp.body = formBody(tid, fl, pg);
    fp.headers['X-Requested-With'] = 'XMLHttpRequest';
    fp.headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
    fp.headers['Accept'] = 'application/json, text/javascript, */*; q=0.01';
    fp.headers['Referer'] = HOST + '/vodshow/' + tid + '-----------.html';
    return post(HOST + '/index.php/ds_api/vod', fp);
}
`;

var PARSER = `
function parseJsonList(text) {
    let videos = [];
    try {
        let o = JSON.parse(text);
        let arr = o.list || [];
        for (let i = 0; i < arr.length; i++) {
            let it = arr[i];
            let id = it.vod_id || '';
            if (!id) id = fst(it.url || '', '/voddetail/(\\\\d+)');
            if (!id) continue;
            let name = cl(it.vod_name || '');
            if (!name) continue;
            videos.push({
                vod_id: HOST + '/voddetail/' + id + '.html',
                vod_name: name,
                vod_pic: absUrl(it.vod_pic || ''),
                vod_remarks: cl(it.vod_remarks || '')
            });
        }
    } catch (e) {}
    return videos;
}
// 搜索结果：<h3 class="slide-info-title">片名</h3>，详情链接在前 2000 字符内
function parseSearch(html) {
    let videos = [];
    let seen = {};
    let h = esc(html);
    let r = new RegExp('<h3 class="slide-info-title[^"]*">([^<]+)</h3>', 'g');
    let m = null;
    while ((m = r.exec(h)) !== null) {
        let name = cl(m[1]);
        if (!name) continue;
        let before = h.slice(Math.max(0, m.index - 2000), m.index);
        let id = last1(before, '/voddetail/(\\\\d+)\\\\.html');
        if (!id || seen[id]) continue;
        seen[id] = 1;
        let pic = last1(before, 'data-src="([^"]+)"');
        let after = h.slice(m.index + m[0].length, m.index + m[0].length + 1200);
        let remarks = fst(after, 'class="slide-info-remarks cor5"[^>]*>([^<]+)<');
        videos.push({
            vod_id: HOST + '/voddetail/' + id + '.html',
            vod_name: name,
            vod_pic: absUrl(pic),
            vod_remarks: cl(remarks)
        });
    }
    return videos;
}
`;

var rule = {
    title: '奈飞工厂',
    host: 'https://netflixgc.org',
    homeUrl: '/',
    url: '/vodshow/fyclass-----------.html',
    searchUrl: '/vodsearch/------------fypage--1---.html?wd=**',
    searchable: 1,
    quickSearch: 1,
    filterable: 1,
    timeout: 15000,
    limit: 24,
    double: false,
    play_parse: true,
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Referer': 'https://netflixgc.org/'
    },
    class_parse: $js.toString(() => {
        input = [
            { type_id: '1', type_name: '电影' },
            { type_id: '2', type_name: '连续剧' },
            { type_id: '3', type_name: '漫剧' },
            { type_id: '23', type_name: '综艺' },
            { type_id: '24', type_name: '纪录片' }
        ];
        let area = ['大陆', '香港', '台湾', '美国', '韩国', '日本', '泰国', '英国',
                    '法国', '德国', '印度', '意大利', '西班牙', '加拿大', '其它'];
        let year = ['2026', '2025', '2024', '2023', '2022', '2021', '2020', '2019',
                    '2018', '2017', '2016', '2015', '2014', '2013', '2012', '更早'];
        let f = {};
        ['1', '2', '3', '23', '24'].forEach(function (tid) {
            f[tid] = [
                { key: 'area', name: '地区', value: [{ n: '全部', v: '' }].concat(area.map(function (a) {
                    return { n: a, v: a };
                })) },
                { key: 'year', name: '年份', value: [{ n: '全部', v: '' }].concat(year.map(function (y) {
                    return { n: y, v: y };
                })) },
                { key: 'by', name: '排序', value: [
                    { n: '默认', v: 'time' }, { n: '人气', v: 'hits' }, { n: '评分', v: 'score' }
                ] }
            ];
        });
        homeObj.filter = f;
    }),
    推荐: `js:${HELPERS}${PARSER}
        VODS = parseJsonList(postVod('1', {}, 1));
    `,
    一级: `js:${HELPERS}${PARSER}
        VODS = parseJsonList(postVod(MY_CATE, MY_FL || {}, MY_PAGE));
    `,
    搜索: `js:${HELPERS}${PARSER}
        let dashes = '';
        for (let i = 0; i < 12; i++) dashes += '-';
        let url = HOST + '/vodsearch/' + dashes + (MY_PAGE > 1 ? String(MY_PAGE) : '') + '--1---.html?wd=' + urlencode(KEY || '');
        let html = request(url);
        VODS = parseSearch(html);
    `,
    二级: `js:${HELPERS}
        let html = esc(request(input));
        let id = fst(input, '/voddetail/(\\\\d+)\\\\.html');

        let name = cl(fst(html, '<h3 class="slide-info-title[^"]*">([^<]+)</h3>'));
        if (!name) name = cl(fst(html, 'data-name="([^"]+)"'));
        if (!name) {
            let t = cl(fst(html, '<title>([^<]+)</title>'));
            let p = t.indexOf('_');
            if (p > 0) t = t.slice(0, p);
            p = t.indexOf(' - ');
            if (p > 0) t = t.slice(0, p);
            name = t.trim();
        }
        if (!name) name = id;

        let pic = '';
        let pm = html.match(new RegExp('<div class="detail-pic">([\\\\s\\\\S]{0,800}?)</div>'));
        if (pm) pic = fst(pm[1], 'data-src="([^"]+)"');
        if (!pic) pic = fst(html, 'data-src="(https?://[^"]+\\\\.(?:jpg|jpeg|png|webp))"');

        // slide-info 行：<strong>导演</strong>值
        let data = {};
        let rows = mall(html, '<div class="slide-info hide partition">([\\\\s\\\\S]{0,2000}?)</div>');
        for (let i = 0; i < rows.length; i++) {
            let blk = rows[i][1];
            let se = blk.indexOf('</strong>');
            let k = se >= 0 ? cl(blk.slice(0, se)) : '';
            let v = se >= 0 ? cl(blk.slice(se + 9)) : '';
            k = k.replace(/[:：]/g, '').trim();
            v = v.replace(/^[：:，,、·]+/, '').replace(/[：:，,、·]+$/, '').trim();
            if (k && v && !data[k]) data[k] = v;
        }

        let year = '';
        let area = '';
        let lang = '';
        let typeName = data['类型'] || '';
        let dm = html.match(new RegExp('<a class="deployment[^"]*"[^>]*>([\\\\s\\\\S]{0,1500}?)</a>'));
        if (dm) {
            let sp = [];
            let sm = mall(dm[1], '<span[^>]*>([\\\\s\\\\S]*?)</span>');
            for (let i = 0; i < sm.length; i++) {
                let t = cl(sm[i][1]);
                if (t && t !== '详情') sp.push(t);
            }
            if (sp.length > 0) year = sp[0];
            if (sp.length > 1) area = sp[1];
            if (sp.length > 2 && !typeName) typeName = sp[2];
            if (sp.length > 3) lang = sp[3];
        }

        let remarks = cl(fst(html, 'class="slide-info-remarks cor5"[^>]*>([^<]+)<'));
        let content = cl(fst(html, '<div id="height_limit"[^>]*>([\\\\s\\\\S]*?)</div>'));
        let actor = data['演员'] || data['主演'] || '';
        let director = data['导演'] || '';
        let score = cl(fst(html, '<div class="fraction">([^<]+)</div>'));

        // 播放源名：anthology-tab 区域里的 <a> 文本
        let froms = [];
        let tm = html.match(new RegExp('<div class="anthology-tab[^"]*">([\\\\s\\\\S]*?)<div class="anthology-list'));
        if (tm && tm[1].length < 8000) {
            let am = mall(tm[1], '<a[^>]*>([\\\\s\\\\S]*?)</a>');
            for (let i = 0; i < am.length; i++) {
                let s = cl(am[i][1]);
                if (s && s.length < 40) froms.push(s);
            }
        }

        let urls = [];
        let lm = mall(html, '<ul class="anthology-list-play[^"]*"[^>]*>([\\\\s\\\\S]*?)</ul>');
        for (let i = 0; i < lm.length; i++) {
            let em = mall(lm[i][1], '<a[^>]+href="(/vodplay/[^"]+)"[^>]*>([^<]*)</a>');
            let list = [];
            for (let k = 0; k < em.length; k++) {
                let label = (em[k][2] || '').trim() || ('第' + (k + 1) + '集');
                list.push(label + '$' + em[k][1]);
            }
            if (list.length > 0) urls.push(list.join('#'));
        }

        VOD = {
            vod_id: input,
            vod_name: name,
            vod_pic: absUrl(pic),
            type_name: typeName,
            vod_year: year,
            vod_area: area,
            vod_lang: lang,
            vod_actor: actor,
            vod_director: director,
            vod_score: score,
            vod_remarks: remarks,
            vod_content: content,
            vod_play_from: froms.length ? froms.join('$$$') : '奈飞工厂',
            vod_play_url: urls.join('$$$')
        };
    `,
    lazy: `js:${HELPERS}
        let url = input;
        if (!isV(url)) {
            let html = request(absUrl(url));
            let raw = fst(html, 'player_\\\\w+\\\\s*=\\\\s*\\\\{[\\\\s\\\\S]*?"url"\\\\s*:\\\\s*"([^"]+)"');
            if (!raw) raw = fst(html, '"url"\\\\s*:\\\\s*"([^"]+)"');
            let real = decodePlay(raw);
            if (real) url = real;
        }
        if (isV(url)) {
            input = { parse: 0, jx: 0, url: url, js: '' };
        } else {
            input = { parse: 1, jx: 0, url: absUrl(input), js: '' };
        }
    `,
};
