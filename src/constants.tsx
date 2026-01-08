import { Movie, ResourceLink, ResourceType, Provider, AuditStatus } from './types';

export const MOCK_USER = {
  id: 'u1',
  username: 'hahhaa',
  role: 'USER',
  score: 120,
  avatar: 'https://picsum.photos/seed/user1/40/40'
} as const;

export const MOCK_MOVIE: Movie = {
  id: 'm1',
  titleCn: '铁血战士：杀戮之地',
  titleEn: 'Predator: Badlands',
  year: 2025,
  posterUrl: 'https://picsum.photos/seed/predator/300/450', // Placeholder
  ratingDouban: 7.4,
  ratingImdb: 7.4,
  ratingTomatoes: 86,
  ratingAudience: 92,
  voteCount: 60658,
  summary: '被部落放逐的年轻铁血战士戴克（迪米特里乌斯·格罗斯-科洛阿玛坦吉 饰），深入一片连宇宙顶级猎手都闻风丧胆的险恶之地。在那里，他将与维兰德-汤谷公司制造的仿生人希雅（艾丽·范宁 饰）意外结盟，这位仅存上半身的仿生人，与他共... [展开全部]',
  tags: ['科幻', '恐怖', '动作'],
  directors: ['丹·特拉亨伯格'],
  writers: ['帕特里克·艾森', '丹·特拉亨伯格'],
  actors: ['艾丽·范宁', '迪米特里乌斯·格罗斯-科洛阿玛坦吉', '拉维·纳亚兰'],
  area: ['美国', '澳大利亚', '新西兰'],
  language: '英语',
  releaseDate: '2025-11-07(美国/中国大陆)',
  duration: '107分钟',
  aliases: ['铁血战士：劣地', '铁血战士：恶土', '终极战士：杀戮星球(台)'],
  status: 'ACTIVE'
};

export const MOCK_RESOURCES: ResourceLink[] = [
  {
    id: 'r1',
    movieId: 'm1',
    type: ResourceType.DRIVE,
    provider: Provider.XUNLEI,
    title: '铁血战士杀戮之地1080p未删减【附1-6季合集】',
    size: '12.4GB',
    url: 'https://pan.xunlei.com/s/VMOCK...',
    code: '',
    uploader: MOCK_USER,
    auditStatus: AuditStatus.PASSED,
    createTime: '5天前'
  },
  {
    id: 'r2',
    movieId: 'm1',
    type: ResourceType.DRIVE,
    provider: Provider.BAIDU,
    title: 'Predator.Badlands.2025.1080p.WEBRip.x264',
    size: '2.4GB',
    url: 'https://pan.baidu.com/s/VMOCK...',
    code: 'ab12',
    uploader: { ...MOCK_USER, username: 'MovieKing' },
    auditStatus: AuditStatus.PASSED,
    createTime: '2天前'
  },
  {
    id: 'r3',
    movieId: 'm1',
    type: ResourceType.DRIVE,
    provider: Provider.QUARK,
    title: '铁血战士：杀戮之地 4K HDR 杜比视界',
    size: '18.1GB',
    url: 'https://pan.quark.cn/s/VMOCK...',
    code: '',
    uploader: { ...MOCK_USER, username: '4KFanatic' },
    auditStatus: AuditStatus.PASSED,
    createTime: '1小时前'
  },
  {
    id: 'r4',
    movieId: 'm1',
    type: ResourceType.MAGNET,
    provider: Provider.NONE,
    title: 'Predator.Badlands.2025.2160p.WEB-DL.DDP5.1.Atmos.DV.MKV',
    size: '14.5GB',
    url: 'magnet:?xt=urn:btih:MOCKHASH123123',
    code: '',
    uploader: { ...MOCK_USER, username: 'TorrentGod' },
    auditStatus: AuditStatus.PASSED,
    createTime: '3小时前'
  }
];
