// Enumerations matching backend DB design
export enum ResourceType {
  MAGNET = 'MAGNET',
  DRIVE = 'DRIVE',
  ONLINE = 'ONLINE'
}

export enum Provider {
  BAIDU = 'Baidu Netdisk',
  QUARK = 'Quark Cloud',
  XUNLEI = 'Thunder Drive',
  ALIYUN = 'Aliyun Drive',
  UC = 'UC Drive',
  MOVE = 'Mobile Cloud',
  NONE = 'Other'
}

export enum AuditStatus {
  PENDING = 0,
  PASSED = 1,
  REJECTED = 2
}

// User Entity
export interface User {
  id: string;
  username: string;
  role: 'ADMIN' | 'USER';
  score: number;
  avatar?: string;
}

// Resource Link Entity
export interface ResourceLink {
  id: string;
  movieId: string;
  type: ResourceType;
  provider: Provider;
  url: string;
  code?: string;
  title: string; // File name
  size: string;
  uploader: User;
  auditStatus: AuditStatus;
  createTime: string;
}

// Movie Metadata Entity
export interface Movie {
  id: string;
  titleCn: string;
  titleEn: string;
  year: number;
  tmdbId?: number;
  posterUrl: string;
  ratingDouban: number;
  ratingImdb: number;
  ratingTomatoes?: number; // percentage
  ratingAudience?: number; // percentage
  voteCount: number;
  summary: string;
  tags: string[]; // JSON stored in DB, parsed array here
  directors: string[];
  writers: string[];
  actors: string[];
  area: string[];
  language: string;
  releaseDate: string;
  duration: string;
  aliases: string[];
  status: 'ACTIVE' | 'INACTIVE';
}