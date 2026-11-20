export const SOCIAL_POST_STATUSES = [
    'PENDING', 'PREPARING', 'PUBLISHING', 'POSTED', 'PREPARE_FAILED', 'UNKNOWN', 'FAILED',
] as const;

export function canRetrySocialPost(status?: string): boolean {
    // Legacy FAILED may represent a lost acknowledgement after an actual publication.
    return status === 'PENDING' || status === 'PREPARE_FAILED';
}

export function socialPostStatusColor(status?: string): string {
    switch (status) {
        case 'POSTED': return 'green';
        case 'PENDING': return 'blue';
        case 'PREPARING':
        case 'PUBLISHING': return 'processing';
        case 'PREPARE_FAILED': return 'red';
        default: return 'orange';
    }
}

export interface SocialPublishTarget {
    id: number;
    platform: 'QQ_CHANNEL' | 'WEIBO';
    accountKey: string;
    name: string;
    targetRef?: string;
    channelRef?: string;
    enabled: boolean;
    autoPostEnabled: boolean;
    scheduleTime: string;
    postsPerRun: number;
    postIntervalSeconds: number;
    template?: string;
    lastAutoRunAt?: string;
}

export interface SocialPostLog {
    id: number;
    targetId: number;
    platform: 'QQ_CHANNEL' | 'WEIBO';
    resourceLinkId: number;
    movieId: string;
    title?: string;
    status: string;
    externalUrl?: string;
    errorMessage?: string;
    postedAt?: string;
    createdAt?: string;
}
