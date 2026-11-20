// Offline-only fixtures. No production credentials or external service requests.
const http = require('node:http');
const day = '2026-10-11';
const overview = {
    date: day, timeZone: 'Asia/Shanghai', requestsToday: 1284, visitorsToday: 173, pageViewsToday: 402,
    clientErrorsToday: 37, serverErrorsToday: 6, searchesToday: 96, resourceOperationsToday: 214,
    socialPostedToday: 12, socialFailedToday: 3, socialPendingToday: 4, socialUnknownToday: 2,
    traffic: [{ day: '2026-09-28', requests: 100, visitors: 40, pageViews: 60 }, { day, requests: 1284, visitors: 173, pageViews: 402 }],
    hotSearches: [{ keyword: '沙丘 2', count: 31 }, { keyword: '繁花', count: 24 }, { keyword: 'The Long Search Term '.repeat(6), count: 12 }],
};
const logs = {
    access: Array.from({ length: 91 }, (_, index) => ({ client_ip: index === 0 ? '2001:db8::42' : index === 1 ? '192.0.2.41' : null,
        user_agent: index === 0 ? 'RawIdentity%_! / "quoted" \\ 😀 ' + 'VeryLongAgentSegment'.repeat(25) + '<img src=x onerror="globalThis.__uaInjected=true">' : index === 1 ? 'Fixture Mobile/1.0' : null,
        id: 91 - index, request_path: index === 90 ? '/api/past-fifty' : '/api/movies/' + (91 - index), method: 'GET', status_code: index % 10 === 0 ? 500 : 200, duration_ms: 42, event_type: 'API', created_at: day + 'T08:30:12' })),
    searches: [{ id: 2, keyword: '沙丘 2', source: 'WEB', result_count: 3, created_at: day + 'T08:30:12' }, { id: 1, keyword: '繁花', source: 'WEB', result_count: 0, created_at: day + 'T08:30:00' }],
    resourceOperations: [{ id: 2, movie_id: 'fixture-mixed', resource_link_id: 2, operation_type: 'SHARE', provider: 'quark', status: 'SUCCESS', created_at: day + 'T08:30:12' }, { id: 1, movie_id: 'fixture-mixed', resource_link_id: 1, operation_type: 'SAVE', provider: 'xunlei', status: 'FAILED', error_message: '分享链接已失效 / Long failure detail '.repeat(12), created_at: day + 'T08:30:00' }],
    qqSearches: [{ id: 2, keyword: 'Fixture movie', movie_id: 'fixture-mixed', resource_count: 3, status: 'LIBRARY_RESOURCE', created_at: day + 'T08:30:12' }, { id: 1, keyword: 'Missing movie', status: 'NO_RESOURCE', resource_count: 0, created_at: day + 'T08:30:00' }],
    socialPosts: ['POSTED', 'PREPARE_FAILED', 'FAILED', 'UNKNOWN', 'PREPARING', 'PUBLISHING', 'PENDING'].map((status, index) => ({ id: 7 - index, title: 'Fixture publishing ' + status, platform: 'QQ_CHANNEL', status, created_at: day + 'T08:30:12', posted_at: status === 'POSTED' ? day + 'T08:35:00' : null, error_message: ['UNKNOWN', 'FAILED', 'PREPARE_FAILED'].includes(status) ? 'Fixture outcome detail' : null })),
};
function logPage(params) {
    const type = params.get('type'), q = (params.get('q') || '').trim().toLowerCase(), status = params.get('status') || 'all';
    let records = [...(logs[type] || [])];
    if (q) records = records.filter(row => Object.values(row).some(value => String(value).toLowerCase().includes(q)));
    if (status !== 'all') records = records.filter(row => {
        if (type === 'access') return status === 'failure' ? row.status_code >= 400 : row.status_code >= 200 && row.status_code < 400;
        if (type === 'searches') return status === 'failure' ? row.result_count === 0 : row.result_count > 0;
        if (status === 'attention') return row.status === 'UNKNOWN';
        if (status === 'pending') return ['PENDING', 'PREPARING', 'PUBLISHING'].includes(row.status);
        return status === 'failure' ? ['FAILED', 'PREPARE_FAILED', 'NO_RESOURCE'].includes(row.status) : ['POSTED', 'SUCCESS', 'LIBRARY_RESOURCE'].includes(row.status);
    });
    const size = Math.min(100, Math.max(1, Number(params.get('size')) || 20)), total = records.length;
    const page = Math.min(Math.max(1, Number(params.get('page')) || 1), Math.max(1, Math.ceil(total / size)));
    return { records: records.slice((page - 1) * size, page * size), total, page, size };
}
const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="450"><rect width="300" height="450" fill="#164e63"/><circle cx="150" cy="175" r="62" fill="#67e8f9"/><path d="M40 420L150 250L260 420Z" fill="#0e7490"/></svg>');
function resource(id, type, provider, movieId) { return { id, movieId, type, provider, name: `Fixture ${type} ${id}`, url: type === 'MAGNET' ? 'magnet:?xt=urn:btih:' + 'a'.repeat(40) : 'https://example.invalid/fixture/' + id, code: '', remark: '', auditStatus: 1, status: 'ACTIVE', linkStatus: 'NORMAL', uploaderId: 1, createdAt: day + 'T08:30:12' }; }
function movieData(id) {
    if (!id.startsWith('fixture-')) return null;
    let resources = [['DISK', 'QUARK'], ['DISK', 'XUNLEI'], ['DISK', 'QUARK'], ['MAGNET', 'OTHER'], ['TORRENT', 'QUARK'], ['TORRENT', 'XUNLEI'], ['TORRENT', 'OTHER'], ['ONLINE', 'OTHER'], ['FUTURE', 'OTHER']].map(([type, provider], index) => resource(index + 1, type, provider, id));
    if (id === 'fixture-empty') resources = [];
    if (id === 'fixture-p2p') resources = resources.filter(row => ['MAGNET', 'TORRENT'].includes(row.type));
    if (id === 'fixture-disk') resources = resources.filter(row => row.type === 'DISK');
    if (id === 'fixture-many') resources = Array.from({ length: 137 }, (_, i) => resource(i + 1, 'TORRENT', 'QUARK', id));
    return { movie: { id, titleCn: '资源分类演示', titleEn: 'Resource counts preview', category: 'mv', year: 2026, runtime: '120', directors: ['Fixture director'], actors: ['Fixture actor'], genres: ['Drama'], regions: ['China'], languages: ['Chinese'], releaseDates: '2026-10-01', aliases: '', posterUrl: image, doubanScore: 8, imdbScore: 8.2, rtScore: '', summary: '隔离测试：网盘视频、磁力与种子归档、在线播放分别计数。', status: 'ACTIVE' }, resources };
}
function startFixtureServer(port = 18085) {
    const server = http.createServer((request, response) => {
        if (request.method !== 'GET') { response.writeHead(405); response.end(); return; }
        const url = new URL(request.url, 'http://127.0.0.1');
        const match = url.pathname.match(/^\/api\/movies\/(fixture-[a-z0-9-]+)$/);
        const body = url.pathname === '/fixture-health' ? { fixtureOnly: true } : match ? movieData(match[1]) : null;
        response.writeHead(body ? 200 : 404, { 'Content-Type': 'application/json', 'X-Gying-Test-Fixture': 'true' });
        response.end(JSON.stringify(body || { error: 'No fixture' }));
    });
    return new Promise(resolve => server.listen(port, '127.0.0.1', () => resolve(server)));
}
module.exports = { overview, logs, logPage, movieData, resource, startFixtureServer };
if (require.main === module) startFixtureServer(Number(process.env.FIXTURE_PORT) || 18085).then(() => console.log('Read-only fixture API listening on loopback'));
