export type CloudDriveProvider = 'QUARK' | 'XUNLEI' | 'BAIDU';

export interface CloudDriveWorkflow {
    provider: CloudDriveProvider;
    supported: boolean;
    status: 'AVAILABLE' | 'PLANNED';
    discoveryEnabled: boolean;
    transferEnabled: boolean;
    gyingPublishEnabled: boolean;
    shareEnabled: boolean;
    credentialConfigured: boolean;
    savePath: string | null;
    batchLimit: number | null;
}

export type CloudDriveWorkflowUpdate = Pick<CloudDriveWorkflow,
    'provider' | 'discoveryEnabled' | 'transferEnabled' | 'gyingPublishEnabled' | 'shareEnabled'> & {
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
    return workflow.provider !== 'BAIDU' && workflow.supported && workflow.status === 'AVAILABLE';
}

export function cloudDriveWorkflowUpdate(workflow: CloudDriveWorkflow | CloudDriveWorkflowUpdate): CloudDriveWorkflowUpdate {
    return {
        provider: workflow.provider,
        discoveryEnabled: workflow.discoveryEnabled,
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
