'use client';

import { useState, useSyncExternalStore } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { Modal } from 'antd';
import { MailOutlined, MessageOutlined, QqOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const CONTACT_EMAIL = '2031798793@qq.com';
const subscribe = () => () => {};

export default function SiteFooter() {
    const [contactOpen, setContactOpen] = useState(false);
    const { t, i18n } = useTranslation();
    // Match the server's default language until hydration finishes.
    const hydrated = useSyncExternalStore(subscribe, () => true, () => false);
    const text = (key: string) => t(key, { lng: hydrated ? i18n.language : 'en' });
    const linkClass = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-3 text-sm !text-gray-500 transition-colors hover:bg-gray-100 hover:!text-blue-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 dark:!text-gray-400 dark:hover:bg-white/5 dark:hover:!text-blue-400';

    return (
        <>
            <footer className="shrink-0 border-t border-gray-200 bg-white px-4 py-4 dark:border-white/10 dark:bg-[#0a0a0a]">
                <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-center gap-2 sm:gap-4">
                    <button
                        type="button"
                        className={`${linkClass} cursor-pointer`}
                        onClick={() => setContactOpen(true)}
                        aria-haspopup="dialog"
                        aria-expanded={contactOpen}
                    >
                        <MailOutlined aria-hidden />
                        {text('contactUs')}
                    </button>
                    <span aria-hidden className="h-3 w-px bg-gray-200 dark:bg-white/15" />
                    <Link href="/messages" className={linkClass}>
                        <MessageOutlined aria-hidden />
                        {text('messageBoard')}
                    </Link>
                </div>
            </footer>

            <Modal
                title={text('contactUs')}
                open={contactOpen}
                onCancel={() => setContactOpen(false)}
                footer={null}
                width={420}
                centered
                styles={{ body: { maxHeight: 'calc(100dvh - 160px)', overflowY: 'auto' } }}
            >
                <div className="flex flex-col items-center gap-4 py-2 text-center">
                    <div>
                        <p className="mb-1 flex items-center justify-center gap-2 font-medium text-blue-600 dark:text-blue-400">
                            <QqOutlined aria-hidden />
                            {text('contactQqPriority')}
                        </p>
                        <p className="m-0 text-sm text-gray-500 dark:text-gray-400">{text('contactQqHint')}</p>
                    </div>
                    <Image
                        src="/images/qq-group.jpg"
                        alt={text('contactQqQrAlt')}
                        width={1327}
                        height={2359}
                        unoptimized
                        className="h-auto w-full max-w-[240px] rounded-xl bg-white"
                    />
                    <div className="w-full rounded-xl bg-gray-50 px-3 py-3 dark:bg-white/5">
                        <a href={`mailto:${CONTACT_EMAIL}`} className="inline-flex min-h-11 items-center gap-2 break-all text-blue-600 hover:underline dark:text-blue-400">
                            <MailOutlined aria-hidden />
                            {CONTACT_EMAIL}
                        </a>
                        <p className="m-0 text-sm text-gray-500 dark:text-gray-400">{text('contactEmailHint')}</p>
                    </div>
                </div>
            </Modal>
        </>
    );
}
