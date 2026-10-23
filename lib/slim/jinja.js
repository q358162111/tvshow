// 保持 jinja 未定义：DRPY2 的 cheerio.jinja2 会 try/catch 调用 jinja.render，
// 抛错后回退到原始 jinja2 —— 即本目录 cheerio.min.js 里的原样返回实现。
