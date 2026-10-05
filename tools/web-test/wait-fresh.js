/*
 * 起客户端之后用它来判断「服务端到底跑的是不是新代码」。
 *
 * 背景：这个环境里反复出现「作业被杀但 JVM 还活着」——旧客户端继续占着 8765，
 * 新客户端 BindException 起不来，于是网页、接口全是旧行为，而排查时完全看不出来。
 * 这里不去猜，直接问接口要一个只有新代码才会有的字段。
 */
const BASE = 'http://127.0.0.1:8765';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const deadline = Date.now() + Number(process.argv[2] || 300000);
    let last = '';
    for (;;) {
        try {
            const s = await (await fetch(BASE + '/api/status', { cache: 'no-store' })).json();
            const search = await (await fetch(BASE + '/api/search?q=' + encodeURIComponent('铁') + '&limit=1')).json();
            const plan = search.items && search.items[0]
                ? await (await fetch(BASE + '/api/plan?id=' + search.items[0].id + '&count=1')).json()
                : null;

            const hasNewJs = (await (await fetch(BASE + '/app.js', { cache: 'no-store' })).text())
                .indexOf('这台客户端上没有这个物品') >= 0;
            const hasSkipField = !!(plan && Object.prototype.hasOwnProperty.call(plan, 'skippedTargets'));

            last = 'handlers=' + s.handlers + ' 新JS=' + hasNewJs + ' 新Java(skippedTargets)=' + hasSkipField;
            if (hasNewJs && hasSkipField) {
                console.log('✓ 服务端已是新代码：' + last);
                process.exit(0);
            }
        } catch (e) {
            last = '还没起来（' + String(e.message).slice(0, 40) + '）';
        }
        if (Date.now() > deadline) {
            console.log('✗ 超时，最后状态：' + last);
            process.exit(1);
        }
        await sleep(4000);
    }
})();
