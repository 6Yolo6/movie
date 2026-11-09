export type CloudDriveProvider = 'QUARK' | 'XUNLEI' | 'BAIDU';

export interface CloudDriveWorkflow {
    provider: CloudDriveProvider;
    supported: boolean;
    status: 'AVAILABLE' | 'PLANNED';
    discoveryEnabled: boolean;
    videoEnabled: boolean;
    torrentEnabled: boolean;
    transferEnabled: boolean;
    gyingPublishEnabled: boolean;
    shareEnabled: boolean;
    credentialConfigured: boolean;
    savePath: string | null;
    batchLimit: number | null;
}

export type CloudDriveWorkflowUpdate = Pick<CloudDriveWorkflow,
    'provider' | 'discoveryEnabled' | 'transferEnabled' | 'gyingPublishEnabled' | 'shareEnabled' | 'videoEnabled' | 'torrentEnabled'> & {
    savePath: string;
    batchLimit: number;
};

export interface CloudDriveTransferResult {
    submitted?: number;
    skipped?: number;
    failed?: number;
}

export const CLOUD_DRIVE_PROVIDERS: CloudDriveProvider[] = ['QUARK', 'XUNLEI', 'BAIDU'];

export function canConfigureCloudDrive(workflow: CloudDriveWorkflow): boolean {
    return workflow.supported && workflow.status === 'AVAILABLE';
}

export function cloudDriveWorkflowUpdate(workflow: CloudDriveWorkflow | CloudDriveWorkflowUpdate): CloudDriveWorkflowUpdate {
    return {
        provider: workflow.provider,
        discoveryEnabled: workflow.discoveryEnabled,
        videoEnabled: workflow.videoEnabled,
        torrentEnabled: workflow.torrentEnabled,
        transferEnabled: workflow.transferEnabled,
        gyingPublishEnabled: workflow.gyingPublishEnabled,
        shareEnabled: workflow.shareEnabled,
        savePath: workflow.savePath?.trim() ?? '',
        batchLimit: workflow.batchLimit ?? 5,
    };
}

export function cloudDriveValidationError(update: CloudDriveWorkflowUpdate): 'savePath' | 'batchLimit' | null {
    const path = update.savePath.trim();
    if (!path.startsWith('/') || path.length > 300 || /[\\\u0000-\u001f\u007f]/.test(path)
        || path.split('/').includes('..')) return 'savePath';
    if (!Number.isInteger(update.batchLimit) || update.batchLimit < 1 || update.batchLimit > 20) return 'batchLimit';
    return null;
}

export type BaiduLoginState = 'DISCONNECTED' | 'PENDING' | 'WAITING_SCAN' | 'CONNECTED' | 'FAILED' | 'EXPIRED';

export interface BaiduSessionStatus {
    configured: boolean;
    state: BaiduLoginState;
    ticketId: string | null;
    expiresAt: number | null;
    accountName: string | null;
    lastValidatedAt: number | null;
    failureCode: string | null;
}

const BAIDU_LOGIN_STATES: BaiduLoginState[] = ['DISCONNECTED', 'PENDING', 'WAITING_SCAN', 'CONNECTED', 'FAILED', 'EXPIRED'];

/** Select only account metadata; session credentials never belong in page state. */
export function baiduSessionStatus(value: unknown): BaiduSessionStatus {
    if (!value || typeof value !== 'object') throw new Error('Invalid Baidu session status');
    const item = value as Record<string, unknown>;
    if (typeof item.configured !== 'boolean' || !BAIDU_LOGIN_STATES.includes(item.state as BaiduLoginState)) {
        throw new Error('Invalid Baidu session status');
    }
    const timestamp = (field: unknown): number | null => {
        if (field == null) return null;
        if (typeof field !== 'number' || !Number.isSafeInteger(field) || field < 0) throw new Error('Invalid Baidu session status');
        return field;
    };
    const text = (field: unknown, maximum: number): string | null => {
        if (field == null) return null;
        if (typeof field !== 'string' || field.length > maximum || /[\u0000-\u001f\u007f]/.test(field)) {
            throw new Error('Invalid Baidu session status');
        }
        return field;
    };
    const status: BaiduSessionStatus = {
        configured: item.configured, state: item.state as BaiduLoginState,
        ticketId: text(item.ticketId, 100), expiresAt: timestamp(item.expiresAt),
        accountName: text(item.accountName, 100), lastValidatedAt: timestamp(item.lastValidatedAt),
        failureCode: text(item.failureCode, 100),
    };
    if (isBaiduLoginPending(status) && (!status.ticketId || !status.expiresAt)) {
        throw new Error('Invalid Baidu session status');
    }
    return status;
}

export function isBaiduLoginPending(status: BaiduSessionStatus | null): boolean {
    return status?.state === 'PENDING' || status?.state === 'WAITING_SCAN';
}


export interface BaiduBackfillSettings {
    enabled: boolean;
    intervalMinutes: number;
    batchLimit: number;
}

export const BAIDU_BACKFILL_STATUSES = ['NOT_STARTED', 'PAUSED', 'PENDING', 'RUNNING', 'BLOCKED', 'COMPLETE'] as const;
export const BAIDU_BACKFILL_PHASES = ['GYING_TORRENTS', 'COMPLETE'] as const;
export const BAIDU_BACKFILL_BLOCKS = ['HUB_DISABLED', 'WORKER_DISABLED', 'TRANSFER_DISABLED', 'SHARE_DISABLED',
    'TORRENT_DISABLED', 'SESSION_REQUIRED', 'GYING_IDENTITY_CHANGED', 'GYING_SOURCE_UNAVAILABLE', 'CHILD_UNKNOWN',
    'CHILD_ACTION_REQUIRED', 'CHILD_REVIEW_REQUIRED', 'CHILD_RUNNING', 'CHILD_FAILED', 'CHILD_RETRY_WAIT',
    'LEASE_BUSY', 'PROCESSING_ERROR', 'UNKNOWN'] as const;
export const BAIDU_BACKFILL_ACTIONS = ['IDLE', 'COLLECT_GYING_TORRENTS', 'ARCHIVE_METADATA_FILES', 'LOAD_SELECTION',
    'PREPARE_METADATA_FILES', 'RESOLVE_MOVIE_FOLDER', 'UPLOAD_TORRENT', 'UPLOAD_MAGNET_TEXT', 'CREATE_METADATA_SHARE',
    'PUBLISH_METADATA_LINK', 'COMPLETE', 'UNKNOWN'] as const;
export const BAIDU_BACKFILL_CHILD_STATUSES = ['PENDING', 'RUNNING', 'FAILED', 'SUCCEEDED', 'UNKNOWN',
    'ACTION_REQUIRED', 'REVIEW_REQUIRED', 'CANCELED'] as const;
export const BAIDU_BACKFILL_CHILD_ERRORS = ['BaiduActionException', 'IllegalStateException', 'IllegalArgumentException',
    'ResponseStatusException', 'OTHER', 'UNKNOWN'] as const;
export const BAIDU_BACKFILL_RESULTS = ['TORRENTS_ARCHIVED', 'NO_ELIGIBLE_GYING_TORRENTS', 'MOVIE_INACTIVE',
    'SELECTION_SUPERSEDED', 'UNKNOWN'] as const;

export interface BaiduBackfillStatus extends BaiduBackfillSettings {
    taskId: number | null;
    status: typeof BAIDU_BACKFILL_STATUSES[number];
    phase: typeof BAIDU_BACKFILL_PHASES[number];
    processed: number;
    succeeded: number;
    skipped: number;
    failed: number;
    nextRunAt: string | null;
    blockedReason: typeof BAIDU_BACKFILL_BLOCKS[number] | null;
    currentIdentityId: number | null;
    currentMovieId: string | null;
    currentGyingType: 'mv' | 'tv' | 'ac' | null;
    currentGyingId: string | null;
    currentChildId: number | null;
    currentResourceIds: number[];
    currentSeedNames: string[];
    currentSeedCount: number;
    currentAction: typeof BAIDU_BACKFILL_ACTIONS[number];
    currentChildStatus: typeof BAIDU_BACKFILL_CHILD_STATUSES[number] | null;
    currentChildError: typeof BAIDU_BACKFILL_CHILD_ERRORS[number] | null;
    lastResult: typeof BAIDU_BACKFILL_RESULTS[number] | null;
    legacyTaskId: number | null;
}

export function baiduBackfillValidationError(settings: BaiduBackfillSettings): 'intervalMinutes' | 'batchLimit' | null {
    if (!Number.isInteger(settings.intervalMinutes) || settings.intervalMinutes < 1 || settings.intervalMinutes > 1440) return 'intervalMinutes';
    if (!Number.isInteger(settings.batchLimit) || settings.batchLimit < 1 || settings.batchLimit > 5) return 'batchLimit';
    return null;
}

/** Scheduling is saved separately from file types, account credentials and workflow stages. */
export function baiduBackfillSettings(value: BaiduBackfillSettings): BaiduBackfillSettings {
    if (typeof value.enabled !== 'boolean' || baiduBackfillValidationError(value)) throw new Error('Invalid Baidu backfill settings');
    return { enabled: value.enabled, intervalMinutes: value.intervalMinutes, batchLimit: value.batchLimit };
}

/** Select bounded identifiers, filenames and fixed codes; upstream payloads never belong in page state. */
export function baiduBackfillStatus(value: unknown): BaiduBackfillStatus {
    if (!value || typeof value !== 'object') throw new Error('Invalid Baidu backfill status');
    const item = value as Record<string, unknown>;
    const settings = baiduBackfillSettings(item as unknown as BaiduBackfillSettings);
    if (!BAIDU_BACKFILL_STATUSES.includes(item.status as BaiduBackfillStatus['status'])
        || !BAIDU_BACKFILL_PHASES.includes(item.phase as BaiduBackfillStatus['phase'])) throw new Error('Invalid Baidu backfill status');
    const count = (field: unknown): number => {
        if (typeof field !== 'number' || !Number.isSafeInteger(field) || field < 0) throw new Error('Invalid Baidu backfill status');
        return field;
    };
    const id = (field: unknown): number | null => {
        if (field == null) return null;
        const value = count(field);
        if (value === 0) throw new Error('Invalid Baidu backfill status');
        return value;
    };
    const text = (field: unknown, maximum: number): string | null => {
        if (field == null) return null;
        if (typeof field !== 'string' || !field.length || field.length > maximum || /[\u0000-\u001f\u007f]/.test(field)) {
            throw new Error('Invalid Baidu backfill status');
        }
        return field;
    };
    const fixed = <T extends string>(field: unknown, values: readonly T[]): T | 'UNKNOWN' | null =>
        field == null ? null : values.includes(field as T) ? field as T : 'UNKNOWN';
    const resourceIds = item.currentResourceIds ?? [];
    if (!Array.isArray(resourceIds) || resourceIds.length > 4) throw new Error('Invalid Baidu backfill status');
    const currentResourceIds = resourceIds.map(value => id(value));
    if (currentResourceIds.some(value => value === null) || new Set(currentResourceIds).size !== currentResourceIds.length) {
        throw new Error('Invalid Baidu backfill status');
    }
    const seedNames = item.currentSeedNames ?? [];
    if (!Array.isArray(seedNames) || seedNames.length > 4) throw new Error('Invalid Baidu backfill status');
    const currentSeedNames = seedNames.map(value => text(value, 255));
    if (currentSeedNames.some(value => value === null)) throw new Error('Invalid Baidu backfill status');
    const currentSeedCount = count(item.currentSeedCount ?? 0);
    if (currentSeedCount > 2) throw new Error('Invalid Baidu backfill status');
    const currentGyingType = item.currentGyingType ?? null;
    if (currentGyingType !== null && !['mv', 'tv', 'ac'].includes(currentGyingType as string)) throw new Error('Invalid Baidu backfill status');
    const currentGyingId = text(item.currentGyingId, 100);
    if (currentGyingId !== null && !/^[A-Za-z0-9]+$/.test(currentGyingId)) throw new Error('Invalid Baidu backfill status');
    const nextRunAt = item.nextRunAt;
    if (nextRunAt != null && (typeof nextRunAt !== 'string'
        || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(nextRunAt)
        || !Number.isFinite(new Date(nextRunAt).getTime()))) throw new Error('Invalid Baidu backfill status');
    return {
        ...settings, taskId: id(item.taskId), status: item.status as BaiduBackfillStatus['status'], phase: item.phase as BaiduBackfillStatus['phase'],
        processed: count(item.processed), succeeded: count(item.succeeded), skipped: count(item.skipped), failed: count(item.failed),
        nextRunAt: nextRunAt == null ? null : nextRunAt as string,
        blockedReason: fixed(item.blockedReason, BAIDU_BACKFILL_BLOCKS),
        currentIdentityId: id(item.currentIdentityId), currentMovieId: text(item.currentMovieId, 64),
        currentGyingType: currentGyingType as BaiduBackfillStatus['currentGyingType'], currentGyingId,
        currentChildId: id(item.currentChildId), currentResourceIds: currentResourceIds as number[],
        currentSeedNames: currentSeedNames as string[], currentSeedCount,
        currentAction: fixed(item.currentAction, BAIDU_BACKFILL_ACTIONS) ?? 'IDLE',
        currentChildStatus: fixed(item.currentChildStatus, BAIDU_BACKFILL_CHILD_STATUSES),
        currentChildError: fixed(item.currentChildError, BAIDU_BACKFILL_CHILD_ERRORS),
        lastResult: fixed(item.lastResult, BAIDU_BACKFILL_RESULTS), legacyTaskId: id(item.legacyTaskId),
    };
}
