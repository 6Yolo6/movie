export interface MovieMetadata {
    category: string;
    id: string;
    titleCn: string;
    titleEn: string;
    year: number;
    runtime: string;
    directors: string[];
    actors: string[];
    genres: string[];
    regions: string[];
    languages: string[];
    releaseDates: string;
    aliases: string;
    posterUrl: string;
    doubanScore: number;
    imdbScore: number;
    rtScore: string;
    summary: string;
    status?: string;
    popularity?: number;
    seriesName?: string;
    season?: number;
}

export interface ResourceLink {
    id: number;
    movieId: string;
    name?: string;
    type: string;
    provider: string;
    url: string;
    code: string;
    uploaderId?: number;
    remark: string;
    auditStatus: number;
    createdAt?: string;
    status?: string;
    linkStatus?: string;
    reportCount?: number;
    quality?: string;
    subtitle?: string;
    fileSize?: string;
    versionNote?: string;
    rejectReason?: string;
    movieTitle?: string;
    uploaderName?: string;
}

export interface MovieDetailDTO {
    movie: MovieMetadata;
    resources: ResourceLink[];
}

export interface HotItem {
    movie: MovieMetadata;
    favoriteCount: number;
}

export interface FavoriteItem extends HotItem {
    favoritedAt: string;
}

export interface UserNotification {
    id: number;
    userId: number;
    type: string;
    title: string;
    content?: string;
    targetType?: string;
    targetId?: string;
    readFlag: boolean;
    createdAt?: string;
}

export interface ResourceReport {
    id: number;
    resourceId: number;
    userId: number;
    reason?: string;
    status: string;
    createdAt?: string;
    handledAt?: string;
    movieId?: string;
    movieTitle?: string;
    resourceName?: string;
    provider?: string;
    url?: string;
    linkStatus?: string;
    uploaderName?: string;
    reporterName?: string;
}