export const RESOURCE_QUICK_PARAMS = [
    'REMUX', '4K/2160P', '1080P', '720P', '中英字幕', '60帧', '简体字幕', '120帧',
    'HDR杜比视界', '繁体字幕', '简繁字幕', '杜比全景声', 'H264', 'H265', 'AV1',
    'WEB-DL', 'BluRay',
];

export function inferResourceProvider(value?: string): string | undefined {
    const url = (value || '').toLowerCase();
    if (url.includes('quark.cn')) return 'QUARK';
    if (url.includes('xunlei.com')) return 'XUNLEI';
    if (url.includes('baidu.com')) return 'BAIDU';
    if (url.includes('aliyundrive.com') || url.includes('alipan.com')) return 'ALIYUN';
    if (url.includes('115.com')) return '115';
    if (url.includes('uc.cn')) return 'UC';
    if (url.includes('123pan.com')) return '123PAN';
    if (url.includes('tianyiyun.com') || url.includes('189.cn')) return 'TIANYI';
    if (url.includes('pikpak')) return 'PIKPAK';
    return undefined;
}

function cleanUrl(value: string): string {
    return value.replace(/[,.!?;:]+$/g, '').replace(/[，。；、）》）】]+$/g, '');
}

export function parseResourceClipboard(text: string): { url?: string; code?: string; name?: string; provider?: string } {
    const links = text.match(/https?:\/\/[^\s"'<>]+/gi) || [];
    const url = links.map(cleanUrl).find((item) => !!inferResourceProvider(item)) || links.map(cleanUrl)[0];
    const codeMatch = text.match(/(?:提取码|取码|密码|pwd)\s*[:：]?\s*([A-Za-z0-9_-]{2,16})/i);
    let name: string | undefined;
    const fileMatch = text.match(/分享文件\s*[：:]\s*([^\r\n]+)/i);
    const quarkMatch = text.match(/分享了\s*[「“"]([^」”"]+)[」”"]/i);
    if (fileMatch) name = fileMatch[1].trim();
    else if (quarkMatch) name = quarkMatch[1].trim();
    if (name) name = name.replace(/[（(][^（）()]*[）)]\s*$/, '').trim();
    return { url, code: codeMatch?.[1], name, provider: inferResourceProvider(url) };
}

export async function readResourceClipboard(): Promise<ReturnType<typeof parseResourceClipboard>> {
    if (!navigator.clipboard?.readText) throw new Error('Clipboard is unavailable');
    return parseResourceClipboard(await navigator.clipboard.readText());
}

export function appendQuickParam(current: string | undefined, parameter: string): string {
    const value = (current || '').trim();
    if (!value) return parameter;
    const tokens = value.split(/\s+/);
    return tokens.includes(parameter) ? value : `${value} ${parameter}`;
}
