/*
 * 本地「新前端 + 现有后端」的反向代理，用来在**不重启游戏**的情况下验证前端改动。
 *
 * 为什么需要它：网页资源是模组从自己的 classpath 里发的，客户端一旦跑起来，
 * 它手里就是启动那一刻的那份 app.js —— 改了前端不重启客户端，量到的全是旧行为。
 * 这个脚本把
 *     /api/*            转发给正在跑的那个客户端（真实数据）
 *     其余（页面资源）   直接读 build/resources/main/... 里的**磁盘文件**（刚构建出来的）
 * 于是浏览器拿到的是新前端、真后端。
 *
 * 用法：node tools/web-test/serve-live.js [端口，默认 8766]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.argv[2] || 8766);
const UPSTREAM = 'http://127.0.0.1:8765';
const WEB_DIR = path.join(__dirname, '..', '..', 'build', 'resources', 'main', 'assets', 'futa_gtnh', 'web');

const TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
    '.json': 'application/json; charset=utf-8'
};

const server = http.createServer(async (req, res) => {
    const url = req.url || '/';

    if (url.startsWith('/api/') || url.startsWith('/api?')) {
        try {
            const upstream = await fetch(UPSTREAM + url, { cache: 'no-store' });
            const body = Buffer.from(await upstream.arrayBuffer());
            res.writeHead(upstream.status, {
                'Content-Type': upstream.headers.get('content-type') || 'application/json; charset=utf-8',
                'Cache-Control': 'no-store',
                'Access-Control-Allow-Origin': '*'
            });
            res.end(body);
        } catch (e) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('上游客户端没在跑：' + e.message);
        }
        return;
    }

    // 页面资源：一律读磁盘（每次请求都重读，改了立刻生效）
    let name = url.split('?')[0];
    if (name === '/' || name === '') name = '/index.html';
    const file = path.join(WEB_DIR, path.normalize(name).replace(/^[\\/]+/, ''));
    if (!file.startsWith(WEB_DIR) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('没有这个文件：' + name);
        return;
    }
    res.writeHead(200, {
        'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream',
        'Cache-Control': 'no-store'
    });
    fs.createReadStream(file).pipe(res);
});

server.listen(PORT, '127.0.0.1', () => {
    console.log('新前端 + 现有后端：http://127.0.0.1:' + PORT + '/  （/api 转发到 ' + UPSTREAM + '）');
    console.log('页面资源目录：' + WEB_DIR);
});
