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
