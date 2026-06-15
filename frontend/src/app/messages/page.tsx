'use client';

import React from 'react';
import { Card, Typography } from 'antd';
import { MessageOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import CommentSection from '@/components/CommentSection';

const { Title, Text } = Typography;

export default function MessagesPage() {
    const { t } = useTranslation();

    return (
        <div className="min-h-[calc(100vh-72px)] bg-gray-50 dark:bg-black px-4 py-8 transition-colors">
            <div className="container mx-auto max-w-5xl">
                <Card className="shadow-sm">
                    <div className="mb-6 flex items-center gap-3">
                        <MessageOutlined className="text-2xl text-blue-500" />
                        <div>
                            <Title level={2} className="!mb-1">{t('messageBoard')}</Title>
                            <Text type="secondary">{t('messageBoardHint')}</Text>
                        </div>
                    </div>
                    <CommentSection relateId="message-board" />
                </Card>
            </div>
        </div>
    );
}
