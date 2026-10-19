'use client';
import Link from 'next/link';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Card, Input, Tag } from 'antd';
import { RobotOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { api, readApiError } from '../../lib/api';
import { useAuthStore } from '../../store/authStore';

interface Movie { id: string; titleCn?: string; titleEn?: string; year?: number; summary?: string; }
interface Resource { url?: string; name?: string; provider?: string; code?: string; }
interface Evidence { tool: string; queriedAt: string; movies?: Movie[]; movie?: Movie; resources?: Resource[]; truncated?: boolean; }
interface Answer { answer: string; evidence: Evidence[]; }
interface Turn { question: string; result?: Answer; error?: string; }
interface Job { jobId: string; status: string; result?: Answer; message?: string; }
const subscribe = () => () => {};
const client = () => true;
const server = () => false;
function safeUrl(value: string | undefined) {
    if (!value || value.length > 600) return null;
    try { const url = new URL(value); return ['https:', 'http:', 'magnet:'].includes(url.protocol) && !url.username && !url.password ? value : null; } catch { return null; }
}
function Conversation() {
    const { t } = useTranslation();
    const [input, setInput] = useState('');
    const [turns, setTurns] = useState<Turn[]>([]);
    const [busy, setBusy] = useState(false);
    const [available, setAvailable] = useState<boolean | null>(null);
    const [error, setError] = useState('');
    const [progress, setProgress] = useState('');
    const controller = useRef<AbortController | null>(null);
    const locked = useRef(false);
    const tail = useRef<HTMLDivElement>(null);
    useEffect(() => {
        let active = true;
        const probe = async () => { try { const response = await api('/api/ai/status'); if (response.ok && active) setAvailable((await response.json()).available); } catch { if (active) setAvailable(false); } };
        void probe(); const timer = setInterval(() => void probe(), 15000);
        return () => { active = false; clearInterval(timer); controller.current?.abort(); };
    }, []);
    useEffect(() => { tail.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [turns, progress]);
    async function send(question = input.trim()) {
        if (!question || locked.current) return;
        locked.current = true; setBusy(true); setError(''); setInput(''); setProgress(t('aiQueued'));
        const abort = new AbortController(); controller.current = abort;
        const history: { role: string; content: string }[] = [];
        let budget = 10000 - question.length;
        for (const turn of turns.filter(turn => turn.result).slice(-4).reverse()) {
            const answer = (turn.result?.answer || '').slice(0, 6000);
            if (turn.question.length + answer.length > budget) break;
            history.unshift({ role: 'user', content: turn.question }, { role: 'assistant', content: answer });
            budget -= turn.question.length + answer.length;
        }
        const index = turns.length;
        setTurns(old => [...old, { question }]);
        try {
            const response = await api('/api/ai/query', { method: 'POST', body: JSON.stringify({ message: question, history }), signal: abort.signal });
            if (!response.ok) throw new Error(await readApiError(response, t('aiFailed')));
            let job: Job = await response.json(); const deadline = Date.now() + 250000;
            while (!['SUCCEEDED', 'FAILED'].includes(job.status)) {
                if (Date.now() > deadline) throw new Error(t('aiFailed'));
                setProgress(job.status === 'RUNNING' ? t('aiThinking') : t('aiQueued'));
                await new Promise<void>((resolve, reject) => {
                    const timer = setTimeout(() => { abort.signal.removeEventListener('abort', cancel); resolve(); }, 2500);
                    const cancel = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
                    abort.signal.addEventListener('abort', cancel, { once: true });
                });
                const poll = await api(`/api/ai/jobs/${encodeURIComponent(job.jobId)}`, { signal: abort.signal });
                if (!poll.ok) throw new Error(await readApiError(poll, t('aiFailed')));
                job = await poll.json();
            }
            if (!job.result || job.status !== 'SUCCEEDED') throw new Error(job.message || t('aiFailed'));
            setTurns(old => old.map((turn, i) => i === index ? { ...turn, result: job.result } : turn));
        } catch (failure) {
            if (!abort.signal.aborted) { const text = failure instanceof Error ? failure.message : t('aiFailed'); setError(text); setTurns(old => old.map((turn, i) => i === index ? { ...turn, error: text } : turn)); setInput(question); }
        } finally { if (!abort.signal.aborted) { setBusy(false); setProgress(''); locked.current = false; } }
    }
    return <main className="mx-auto max-w-4xl px-4 py-8">
        <div className="mb-5 flex items-center justify-between gap-3"><h1 className="m-0 text-2xl font-semibold"><RobotOutlined className="mr-2 text-blue-500" />{t('navAiAssistant')}</h1><Button disabled={busy} onClick={() => { setTurns([]); setError(''); }}>{t('aiNewChat')}</Button></div>
        <p className="text-sm text-slate-500">{t('aiDescription')}</p>
        {available === false && <Alert type="warning" showIcon title={t('aiOffline')} className="mb-4" />}
        <Card>
            {turns.length === 0 && <div className="py-8 text-center"><p>{t('aiWelcome')}</p><div className="flex flex-wrap justify-center gap-2">{[t('aiExample1'), t('aiExample2')].map(example => <Button key={example} onClick={() => { setInput(example); }}>{example}</Button>)}</div></div>}
            <div aria-live="polite" className="space-y-6">
                {turns.map((turn, index) => <section key={index}>
                    <div className="ml-auto max-w-[90%] whitespace-pre-wrap break-words rounded-2xl bg-blue-50 p-4 dark:bg-blue-950/40">{turn.question}</div>
                    {turn.result && <div className="mt-3 rounded-2xl border border-slate-200 p-4 dark:border-slate-700">
                        <p className="whitespace-pre-wrap break-words leading-7">{turn.result.answer}</p>
                        {turn.result.evidence.map((evidence, n) => <div key={n} className="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
                            <p className="text-xs text-slate-500">{t('aiCatalogEvidence')} · {new Date(evidence.queriedAt).toLocaleString()} {evidence.truncated && <Tag>{t('aiPartial')}</Tag>}</p>
                            {(evidence.movies || (evidence.movie ? [evidence.movie] : [])).map(movie => <Link key={movie.id} href={`/movie/${encodeURIComponent(movie.id)}`} className="mb-2 block rounded-lg bg-slate-50 p-3 text-blue-600 dark:bg-slate-800 dark:text-blue-400">{movie.titleCn || movie.titleEn} {movie.year && `(${movie.year})`} · {t('aiViewDetails')}</Link>)}
                            {evidence.resources?.map((resource, r) => { const url = safeUrl(resource.url); return url && <a key={r} href={url} target="_blank" rel="noopener noreferrer" className="mb-2 block break-all text-blue-600 dark:text-blue-400">{resource.provider} · {resource.name || t('aiOpenResource')}{resource.code && ` · ${t('aiCode')}: ${resource.code}`}</a>; })}
                        </div>)}
                    </div>}
                    {turn.error && <Alert type="error" title={turn.error} showIcon className="mt-3" />}
                </section>)}
                <div ref={tail} />
            </div>
            {error && <p role="alert" className="text-red-500">{error}</p>}
            <div className="mt-6 flex items-end gap-2"><Input.TextArea value={input} onChange={event => setInput(event.target.value)} maxLength={1000} autoSize={{ minRows: 2, maxRows: 5 }} placeholder={t('aiPlaceholder')} aria-label={t('aiPlaceholder')} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} /><Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!input.trim() || available === false} onClick={() => void send()}>{t('aiSend')}</Button></div>
            <p role="status" className="mb-0 mt-2 text-xs text-slate-500">{progress || t('aiHint')}</p>
        </Card>
    </main>;
}
export default function AiAssistantPage() {
    const { user } = useAuthStore(); const { t } = useTranslation();
    const hydrated = useSyncExternalStore(subscribe, client, server);
    if (!hydrated) return <main className="mx-auto max-w-4xl px-4 py-10" aria-busy="true">{t('aiLoading')}</main>;
    if (!user) return <main className="mx-auto max-w-4xl px-4 py-10"><Card title={t('navAiAssistant')}><Alert showIcon type="info" title={t('aiLogin')} className="mb-4" /><Link href="/login?redirect=%2Fai-assistant"><Button type="primary">{t('loginSubmit')}</Button></Link></Card></main>;
    return <Conversation key={user.id} />;
}
