'use client';

import { useState } from 'react';
import { App, Button, Card, Input, List, QRCode, Space, Tag, Typography } from 'antd';
import { SearchOutlined, CopyOutlined } from '@ant-design/icons';
import { api, readApiError } from '../../lib/api';
import { useAuthStore } from '../../store/authStore';

interface SearchLink { url: string; name: string; }
interface SearchResult { reply: string; links: SearchLink[]; }

export default function ResourceSearchPage() {
    const { message } = App.useApp();
    const { user } = useAuthStore();
    const [keyword, setKeyword] = useState('');
    const [loading, setLoading] = useState(false);
    const [result, setResult] = useState<SearchResult | null>(null);

    const submit = async () => {
        const value = keyword.trim();
        if (!value) { message.warning('请输入影片名或候选序号'); return; }
        setLoading(true);
        try {
            const response = await api('/api/resource-search/query', {
                method: 'POST',
                body: JSON.stringify({ keyword: value }),
            });
            if (!response.ok) { message.error(await readApiError(response, '搜索失败')); return; }
            setResult(await response.json());
        } catch { message.error('网络连接失败，请稍后重试'); }
        finally { setLoading(false); }
    };

    const copy = async (url: string) => {
        try { await navigator.clipboard.writeText(url); message.success('链接已复制'); }
        catch { message.error('复制失败，请手动复制'); }
    };

    if (!user) {
        return <main className="mx-auto max-w-3xl px-4 py-10"><Card title="搜索影片资源">请先登录后使用资源搜索与临时转存。</Card></main>;
    }

    return <main className="mx-auto max-w-5xl px-4 py-8">
        <Card title="搜索影片资源" extra={<Tag color="blue">夸克 / 迅雷</Tag>}>
            <Typography.Paragraph type="secondary">
                搜索 GYING 与 PanSou 候选，按回复中的序号继续选择；临时转存成功后会按系统设置自动清理，正式片库资源不会被删除。
            </Typography.Paragraph>
            <Space.Compact style={{ width: '100%' }}>
                <Input value={keyword} onChange={event => setKeyword(event.target.value)} onPressEnter={submit}
                    placeholder="输入影片名，或继续输入候选序号" maxLength={80} />
                <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={submit}>搜索 / 继续</Button>
            </Space.Compact>
        </Card>
        {result && <Card className="mt-5" title="处理结果">
            <pre className="whitespace-pre-wrap break-words text-sm leading-6">{result.reply}</pre>
            {result.links?.length > 0 && <List className="mt-5" header="可用链接与二维码" dataSource={result.links} renderItem={item => <List.Item>
                <div className="flex w-full flex-col gap-4 md:flex-row md:items-center md:justify-between">
                    <div className="min-w-0 flex-1"><Typography.Text strong>{item.name}</Typography.Text><div className="mt-2 break-all text-sm">{item.url}</div>
                        <Button className="mt-2" icon={<CopyOutlined />} onClick={() => copy(item.url)}>复制链接</Button></div>
                    <QRCode value={item.url} size={150} errorLevel="M" />
                </div>
            </List.Item>} />}
        </Card>}
    </main>;
}
