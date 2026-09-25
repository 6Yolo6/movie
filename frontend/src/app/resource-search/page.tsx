'use client';

import { Suspense, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { App, Alert, Button, Card, Input, QRCode, Tag, Typography } from 'antd';
import type { InputRef } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, QrcodeOutlined, SearchOutlined, SendOutlined } from '@ant-design/icons';
import { api, readApiError } from '../../lib/api';
import { MAX_SEARCH_TURNS, parseSearchReply, readSearchHistory, safeSearchLinks, stripSearchUrls } from '../../lib/resourceSearch';
import type { SearchLink, SearchTurn } from '../../lib/resourceSearch';
import { useSearchParams } from 'next/navigation';
import { useAuthStore } from '../../store/authStore';

interface SearchJob { jobId: string; status: string; reply?: string; links?: SearchLink[]; message?: string; }

function ResourceLinks({ links }: { links: SearchLink[] }) {
    return <div className="mt-4 grid gap-3 sm:grid-cols-2">
        {links.map((link, index) => {
            const host = new URL(link.url).hostname;
            const app = host === 'pan.quark.cn' ? '夸克 App' : host === 'pan.xunlei.com' ? '迅雷 App' : '对应手机 App';
            return <div key={link.url} data-testid="resource-qr-card" className="flex min-w-0 flex-col items-center rounded-xl border border-blue-100 bg-blue-50/50 p-4 text-center dark:border-blue-950 dark:bg-blue-950/20">
                <div className="mb-3 flex max-w-full items-center gap-2"><QrcodeOutlined className="shrink-0 text-blue-500" /><Typography.Text strong className="break-words">{stripSearchUrls(link.name) || '资源分享'}</Typography.Text><Tag bordered={false}>{index + 1}</Tag></div>
                {new TextEncoder().encode(link.url).length <= 1000
                    ? <div className="rounded-xl bg-white p-2" aria-label={`${app}扫码二维码`}><QRCode value={link.url} size={180} errorLevel="M" color="#111827" bgColor="#ffffff" /></div>
                    : <Alert type="warning" showIcon title="该资源地址过长，无法生成清晰二维码，请选择其他资源" />}
                <p className="mb-0 mt-3 text-sm font-medium text-blue-600 dark:text-blue-400">请使用{app}扫一扫</p>
            </div>;
        })}
        {links.length > 0 && <p className="text-xs text-slate-500 sm:col-span-2">扫码后请及时保存；临时分享会按系统设置清理，二维码以实际访问结果为准。</p>}
    </div>;
}

function SearchReply({ turn, active, busy, onAction }: {
    turn: SearchTurn; active: boolean; busy: boolean;
    onAction: (command: string, label: string) => void;
}) {
    const reply = parseSearchReply(turn.reply);
    return <div className="min-w-0">
        <div className="space-y-2">
            {reply.parts.map((part, index) => part.type === 'text'
                ? <div key={index} className="whitespace-pre-wrap break-words text-sm leading-7 text-slate-700 dark:text-slate-200">{part.text.trim()}</div>
                : <button key={index} type="button" data-command={part.command}
                    disabled={!active || busy}
                    onClick={() => onAction(part.command, `选择${part.kind === 'movie' ? '影片' : '资源'} ${part.command}：${part.title}`)}
                    className="group flex w-full items-center gap-3 rounded-xl border border-slate-200 bg-white p-3 text-left transition-colors enabled:hover:border-blue-400 enabled:hover:bg-blue-50 focus-visible:outline-2 focus-visible:outline-blue-500 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:enabled:hover:bg-blue-950/30">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-xs font-semibold text-blue-600 dark:bg-blue-950">{part.command}</span>
                    <span className="min-w-0 flex-1 break-words text-sm font-medium text-slate-800 dark:text-slate-100">{part.title}</span>
                    {part.badge && <Tag className="!m-0 shrink-0" color={part.badge === '夸克' ? 'blue' : part.badge === '迅雷' ? 'cyan' : 'default'}>{part.badge}</Tag>}
                    <ArrowRightOutlined className="shrink-0 text-slate-400" />
                </button>)}
        </div>
        <ResourceLinks links={turn.links} />
        {(reply.hasMoreResources || (reply.page && reply.page.total > 1)) && <div className="mt-4 flex flex-wrap items-center gap-2">
            {reply.hasMoreResources && <Button data-command="资源" disabled={!active || busy} onClick={() => onAction('资源', reply.libraryFirst ? '搜索其他资源' : '查看其他资源')}>{reply.libraryFirst ? '搜索其他资源' : '查看其他资源'}</Button>}
            {reply.page && reply.page.total > 1 && <>
                <Button size="small" data-command="上一页" icon={<ArrowLeftOutlined />} disabled={!active || busy || reply.page.current <= 1} onClick={() => onAction('上一页', '上一页资源')}>上一页</Button>
                <span className="px-1 text-xs text-slate-500">第 {reply.page.current} / {reply.page.total} 页</span>
                <Button size="small" data-command="下一页" icon={<ArrowRightOutlined />} disabled={!active || busy || reply.page.current >= reply.page.total} onClick={() => onAction('下一页', '下一页资源')}>下一页</Button>
            </>}
        </div>}
        {(reply.hasChoices || reply.hasMoreResources) && <p className="mt-3 text-xs text-slate-500">{active ? '点击候选即可继续，也可以在下方输入序号或新片名。' : '历史候选仅供查看，请使用最新回复；刷新后可重新搜索片名。'}</p>}
    </div>;
}

function SearchConversation({ userId, entryKeyword = '', autoSearch = false }: { userId: number; entryKeyword?: string; autoSearch?: boolean }) {
    const { message } = App.useApp();
    const [keyword, setKeyword] = useState(entryKeyword);
    const [loading, setLoading] = useState(false);
    const [jobId, setJobId] = useState<string | null>(null);
    const [turns, setTurns] = useState<SearchTurn[]>([]);
    const [ready, setReady] = useState(false);
    const [activeReplyId, setActiveReplyId] = useState<string | null>(null);
    const [progress, setProgress] = useState('');
    const [sendError, setSendError] = useState('');
    const inputRef = useRef<InputRef>(null);
    const scrollRef = useRef<HTMLDivElement>(null);
    const busy = useRef(false);
    const alive = useRef(true);
    const postController = useRef<AbortController | null>(null);
    const autoSearchHandled = useRef(false);
    const submitRef = useRef<(command?: string, label?: string) => Promise<void>>(async () => {});
    const storageKey = `resource-search-job:${userId}`;
    const historyKey = `resource-search-history:${userId}`;

    useEffect(() => {
        alive.current = true;
        try {
            const history = readSearchHistory(sessionStorage.getItem(historyKey));
            const saved = sessionStorage.getItem(storageKey);
            const pending = saved && /^[a-zA-Z0-9-]{1,100}$/.test(saved) ? saved : null;
            setTurns(history.map(turn => turn.status === 'pending' && turn.id !== pending
                ? { ...turn, status: 'error', reply: '任务状态已失效，请重新搜索片名。' } : turn));
            if (pending) { setJobId(pending); setLoading(true); busy.current = true; }
        } catch { /* Session storage is optional. */ }
        setReady(true);
        return () => { alive.current = false; postController.current?.abort(); };
    }, [historyKey, storageKey]);

    useEffect(() => {
        if (!ready) return;
        try { sessionStorage.setItem(historyKey, JSON.stringify(turns.slice(-MAX_SEARCH_TURNS))); }
        catch { /* Do not interrupt a search when storage is full/disabled. */ }
    }, [turns, ready, historyKey]);

    useEffect(() => {
        const panel = scrollRef.current;
        if (panel) panel.scrollTop = panel.scrollHeight;
    }, [turns, loading]);

    useEffect(() => {
        if (!jobId) return;
        let stopped = false;
        let timer: ReturnType<typeof setTimeout>;
        const controller = new AbortController();
        const finish = (status: 'done' | 'error', reply: string, links: SearchLink[] = []) => {
            if (stopped) return;
            setTurns(current => {
                const existing = current.find(turn => turn.id === jobId);
                const result: SearchTurn = { id: jobId, command: existing?.command || '', label: existing?.label || '继续上次请求', createdAt: existing?.createdAt || Date.now(), status, reply, links };
                return (existing ? current.map(turn => turn.id === jobId ? result : turn) : [...current, result]).slice(-MAX_SEARCH_TURNS);
            });
            setActiveReplyId(status === 'done' ? jobId : null);
            try { sessionStorage.removeItem(storageKey); } catch { /* Optional persistence. */ }
            setJobId(null); setLoading(false); busy.current = false; setProgress('');
        };
        const poll = async () => {
            try {
                const response = await api(`/api/resource-search/jobs/${encodeURIComponent(jobId)}`, { signal: controller.signal });
                if (stopped) return;
                if ([404, 401, 403].includes(response.status)) {
                    finish('error', await readApiError(response, '任务不可用，请重新搜索片名'));
                    return;
                }
                if (!response.ok) throw new Error('poll unavailable');
                const job: SearchJob = await response.json();
                if (stopped) return;
                if (job.status === 'SUCCEEDED') { finish('done', job.reply || '处理完成，暂无更多回复。', safeSearchLinks(job.links)); return; }
                if (job.status === 'FAILED') { finish('error', job.message || '搜索暂时不可用，请重新搜索片名。'); return; }
                setProgress(job.status === 'QUEUED' ? '请求已收到，正在排队…' : '正在查询来源或处理转存，请稍候…');
            } catch {
                if (stopped) return;
                setProgress('连接暂时中断，正在恢复结果；不会重复启动转存。');
            }
            if (!stopped) timer = setTimeout(poll, 2000);
        };
        void poll();
        return () => { stopped = true; clearTimeout(timer); controller.abort(); };
    }, [jobId, storageKey]);

    const submit = async (command?: string, label?: string) => {
        if (busy.current || !ready) return;
        const value = (command ?? keyword).trim();
        if (!value) { inputRef.current?.focus(); message.warning('请输入片名，或点击上方候选'); return; }
        busy.current = true; setLoading(true); setSendError('');
        const controller = new AbortController(); postController.current = controller;
        try {
            const response = await api('/api/resource-search/query', {
                method: 'POST', body: JSON.stringify({ keyword: value }), signal: controller.signal,
            });
            if (!alive.current) return;
            if (!response.ok) {
                const error = await readApiError(response, '提交失败，请稍后重试');
                if (!alive.current) return;
                setSendError(error); setLoading(false); busy.current = false;
                return;
            }
            const job: SearchJob = await response.json();
            if (!alive.current) return;
            if (!job.jobId) throw new Error('missing job');
            // Clear only the submitted draft. Text typed while the request was in flight is preserved.
            if (command === undefined) setKeyword(current => current.trim() === value ? '' : current);
            setActiveReplyId(null);
            setTurns(current => current.some(turn => turn.id === job.jobId) ? current : [...current, {
                id: job.jobId, command: value, label: label || value, status: 'pending' as const,
                reply: '', links: [], createdAt: Date.now(),
            }].slice(-MAX_SEARCH_TURNS));
            try { sessionStorage.setItem(storageKey, job.jobId); } catch { /* Optional persistence. */ }
            setProgress('请求已收到，正在获取结果…'); setJobId(job.jobId);
            inputRef.current?.focus({ preventScroll: true });
        } catch {
            if (!alive.current) return;
            setActiveReplyId(null);
            setSendError('提交结果未确认，输入内容已保留。若已提交过资源序号，请先重新搜索片名，不要重复转存。');
            setLoading(false); busy.current = false;
        }
    };

    useEffect(() => {
        submitRef.current = submit;
    });

    useEffect(() => {
        if (!ready || !autoSearch || autoSearchHandled.current) return;
        autoSearchHandled.current = true;
        const timer = setTimeout(() => { void submitRef.current(entryKeyword); }, 0);
        return () => clearTimeout(timer);
    }, [ready, autoSearch, entryKeyword]);

    const latest = turns[turns.length - 1];
    return <main className="mx-auto max-w-4xl px-3 py-5 sm:px-6 sm:py-8">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
            <div><Typography.Title level={3} className="!mb-1">搜索影片资源</Typography.Title><p className="text-sm text-slate-500">先查片库已有资源 → 手机 App 扫码保存</p></div>
            <Tag color="blue">夸克 / 迅雷</Tag>
        </div>
        <Card styles={{ body: { padding: 0 } }} className="overflow-hidden shadow-sm">
            <div ref={scrollRef} data-testid="search-conversation" className="space-y-6 overflow-y-auto overscroll-contain p-4 sm:p-6" style={{ minHeight: 260, maxHeight: 'min(65dvh, 760px)' }}>
                {!turns.length && !loading && <div className="flex min-h-56 flex-col items-center justify-center gap-3 text-center">
                    <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-50 text-2xl text-blue-500 dark:bg-blue-950"><SearchOutlined /></div>
                    <Typography.Text strong>想找什么影片？</Typography.Text>
                    <p className="max-w-sm text-sm leading-6 text-slate-500">在下方输入片名，结果会显示在这里。<br />有多个候选时，直接点击即可继续，不用手动输入序号。</p>
                </div>}
                {turns.map((turn, index) => <section key={turn.id} data-testid="search-turn" aria-label={`第 ${index + 1} 次请求`}>
                    <div className="mb-3 flex justify-end"><div className="max-w-[90%] break-words rounded-2xl rounded-tr-sm bg-blue-600 px-4 py-2.5 text-sm leading-6 text-white">{turn.label}</div></div>
                    <div className="rounded-2xl rounded-tl-sm bg-slate-50 p-4 dark:bg-slate-800/60">
                        <div className="mb-2 flex items-center gap-2 text-xs font-medium text-slate-500"><SearchOutlined />资源助手{turn.status === 'done' && turn.id === activeReplyId && <Tag color="green" className="!m-0">最新回复</Tag>}</div>
                        {turn.status === 'pending' ? <div className="flex items-center gap-2 py-2 text-sm text-slate-500"><span className="h-2 w-2 animate-pulse rounded-full bg-blue-500" />{progress || '正在处理…'}</div>
                            : turn.status === 'error' ? <Alert type="warning" showIcon title="这次请求未完成" description={stripSearchUrls(turn.reply)} />
                                : <SearchReply turn={turn} active={turn.id === activeReplyId && turn.id === latest?.id} busy={loading} onAction={(command, label) => void submit(command, label)} />}
                    </div>
                </section>)}
                {loading && !turns.some(turn => turn.status === 'pending') && <p className="text-sm text-slate-500">{progress || '正在提交…'}</p>}
            </div>
            <div className="border-t border-slate-100 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-5">
                {sendError && <Alert className="mb-3" type="warning" showIcon title={sendError} closable onClose={() => setSendError('')} />}
                <div className="flex items-center gap-2">
                    <Input ref={inputRef} value={keyword} onChange={event => setKeyword(event.target.value)}
                        onPressEnter={event => { if (!event.nativeEvent.isComposing && event.keyCode !== 229) { event.preventDefault(); void submit(); } }}
                        aria-label="影片名或回复" allowClear size="large" autoComplete="off" maxLength={80}
                        placeholder={loading ? '可以先写下一条，当前任务完成后发送' : turns.length ? '回复序号，或输入新的片名…' : '输入影片名开始搜索…'} />
                    <Button type="primary" size="large" icon={<SendOutlined />} loading={loading} disabled={!ready || !keyword.trim()} onClick={() => void submit()} className="shrink-0">发送</Button>
                </div>
                <p role="status" aria-live="polite" className="mb-0 mt-2 text-xs leading-5 text-slate-500">{loading ? progress || '正在提交…' : '发送后自动清空输入框 · 按 Enter 发送 · 历史回复保留在当前标签页'}</p>
            </div>
        </Card>
    </main>;
}

const subscribeHydration = () => () => {};
const clientHydration = () => true;
const serverHydration = () => false;

function ResourceSearchContent() {
    const { user } = useAuthStore();
    const mounted = useSyncExternalStore(subscribeHydration, clientHydration, serverHydration);
    const searchParams = useSearchParams();
    const entryKeyword = (searchParams.get('keyword') || '').trim().slice(0, 120);
    const autoSearch = entryKeyword.length > 0 && searchParams.get('auto') === '1';
    if (!mounted) return <main className="mx-auto max-w-4xl px-4 py-10" aria-busy="true">正在加载资源搜索…</main>;
    if (!user) return <main className="mx-auto max-w-4xl px-4 py-10"><Card title="搜索影片资源">请先登录后使用资源搜索与临时转存。</Card></main>;
    return <SearchConversation key={`${user.id}:${entryKeyword}`} userId={user.id} entryKeyword={entryKeyword} autoSearch={autoSearch} />;
}

export default function ResourceSearchPage() {
    return <Suspense fallback={<main className="mx-auto max-w-4xl px-4 py-10" aria-busy="true">正在加载资源搜索…</main>}><ResourceSearchContent /></Suspense>;
}
