export type ResourceCategory = 'disk' | 'p2p' | 'online' | 'other';

// Classify the content, not its transport: a torrent archive on Quark is still P2P.
export function resourceCategory(type: unknown): ResourceCategory {
    switch (typeof type === 'string' ? type.trim().toUpperCase() : '') {
        case 'DISK': return 'disk';
        case 'MAGNET':
        case 'TORRENT': return 'p2p';
        case 'ONLINE': return 'online';
        default: return 'other';
    }
}

export function groupResources<T extends { type?: string | null }>(resources: T[]): Record<ResourceCategory, T[]> {
    const groups: Record<ResourceCategory, T[]> = { disk: [], p2p: [], online: [], other: [] };
    for (const resource of resources) groups[resourceCategory(resource.type)].push(resource);
    return groups;
}

// Resource createdAt is a server-local DATETIME. Preserve its calendar date during SSR,
// rather than allowing the server/browser OS locale to produce different text.
export function resourceCreatedDate(value: unknown): string {
    if (typeof value !== 'string') return '—';
    return /^(\d{4}-\d{2}-\d{2})(?:T| |$)/.exec(value)?.[1] ?? '—';
}
