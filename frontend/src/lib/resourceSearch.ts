export interface SearchLink { url: string; name: string; }
export interface SearchTurn {
    id: string;
    command: string;
    label: string;
    status: 'pending' | 'done' | 'error';
    reply: string;
    links: SearchLink[];
    createdAt: number;
}
export type ReplyPart = { type: 'text'; text: string } | {
    type: 'choice'; command: string; title: string; badge?: string; kind: 'movie' | 'resource';
};

export const MAX_SEARCH_TURNS = 20;

export function safeSearchLinks(value: unknown): SearchLink[] {
    if (!Array.isArray(value)) return [];
    const seen = new Set<string>();
    return value.flatMap(item => {
        if (!item || typeof item.url !== 'string' || item.url.length > 4096 || seen.has(item.url)) return [];
        try {
            const url = new URL(item.url);
            if (!['https:', 'http:', 'magnet:'].includes(url.protocol)) return [];
            seen.add(item.url);
            const provider = url.hostname === 'pan.quark.cn' ? '夸克分享'
                : url.hostname === 'pan.xunlei.com' ? '迅雷分享' : url.protocol === 'magnet:' ? '磁力链接' : '资源链接';
            const name = typeof item.name === 'string' && !/^资源链接\s*\d+$/.test(item.name)
                ? item.name.slice(0, 160) : provider;
            return [{ url: item.url, name }];
        } catch { return []; }
    }).slice(0, 10);
}

/** Resource addresses are encoded in QR only, never rendered in reply text or labels. */
export function stripSearchUrls(text: string): string {
    return text.replace(/(?:https?:\/\/|magnet:\?)[^\s<>]+/gi, '').replace(/\n[ \t]*\n(?:[ \t]*\n)+/g, '\n\n');
}

/** Only turn the backend's explicit candidate sections into commands, never arbitrary numbered text. */
export function parseSearchReply(reply: string) {
    const parts: ReplyPart[] = [];
    let kind: 'movie' | 'resource' | null = null;
    let sawChoice = false;
    const text = (line: string) => {
        const last = parts[parts.length - 1];
        if (last?.type === 'text') last.text += '\n' + line;
        else parts.push({ type: 'text', text: line });
    };
    for (const line of stripSearchUrls(reply).split(/\r?\n/)) {
        if (line === '请选择要搜索的影片：') {
            kind = 'movie'; text('找到这些影片，点击选择：'); continue;
        }
        if (/^请选择资源（/.test(line) || line === '如需其他版本或网盘，请继续回复下面的资源序号：') {
            kind = 'resource'; text('选择一个资源版本：'); continue;
        }
        const match = kind && line.match(/^(\d{1,2})\.\s+(.+)$/);
        if (match && Number(match[1]) >= 1 && Number(match[1]) <= 30) {
            const badge = match[2].match(/\s+\[([^\]]+)\]$/);
            parts.push({ type: 'choice', command: match[1], title: badge ? match[2].slice(0, badge.index) : match[2], badge: badge?.[1], kind: kind! });
            sawChoice = true; continue;
        }
        if (kind) kind = null;
        if (sawChoice && line === '直接回复序号即可，例如：1') continue;
        if (line.startsWith('还可以继续选择其他资源：回复当前页序号')) continue;
        text(line);
    }
    const page = reply.match(/当前第\s*(\d+)\/(\d+)\s*页/);
    const current = page ? Number(page[1]) : 0;
    const total = page ? Number(page[2]) : 0;
    const hasContinuation = reply.includes('还可以继续选择其他资源：回复当前页序号');
    const libraryFirst = reply.includes('需要其他版本或网盘，可点击“搜索其他资源”继续。');
    return {
        parts: parts.filter(part => part.type !== 'text' || part.text.trim()),
        hasChoices: sawChoice,
        hasMoreResources: (hasContinuation || libraryFirst) && !sawChoice,
        libraryFirst,
        page: hasContinuation && current > 0 && current <= total ? { current, total } : null,
    };
}

export function readSearchHistory(raw: string | null): SearchTurn[] {
    try {
        const value: unknown = JSON.parse(raw || '[]');
        if (!Array.isArray(value)) return [];
        return value.filter(item => item && typeof item.id === 'string' && item.id.length <= 100
            && typeof item.command === 'string' && typeof item.label === 'string'
            && ['pending', 'done', 'error'].includes(item.status) && typeof item.reply === 'string'
            && typeof item.createdAt === 'number' && Number.isFinite(item.createdAt))
            .slice(-MAX_SEARCH_TURNS).map(item => ({
                id: item.id, command: item.command.slice(0, 80), label: item.label.slice(0, 200),
                status: item.status, reply: item.reply.slice(0, 20000),
                links: safeSearchLinks(item.links), createdAt: item.createdAt,
            }));
    } catch { return []; }
}
