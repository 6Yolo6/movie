'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Avatar, Input, Button, Form, message, Spin, Typography, Popconfirm } from 'antd';
import { useAuthStore } from '@/store/authStore';
import { api } from '@/lib/api';
import { formatDistanceToNow } from 'date-fns/formatDistanceToNow';
import { zhCN, enUS } from 'date-fns/locale';
import { useTranslation } from 'react-i18next';
import { DeleteOutlined, LikeFilled, LikeOutlined, MessageOutlined } from '@ant-design/icons';

const { TextArea } = Input;
const { Text } = Typography;

interface CommentItem {
    id: number;
    relateId: string;
    userId: number;
    username: string;
    nickname?: string;
    content: string;
    createdAt: string;
    upvotes: number;
    parentId: number;
    replyToNickname?: string;
    replies?: CommentItem[];
}

interface PaginatedComments {
    records: CommentItem[];
    total: number;
    current: number;
    size: number;
    pages: number;
}

interface CommentSectionProps {
    relateId: string;
}

const CommentSection: React.FC<CommentSectionProps> = ({ relateId }) => {
    const [comments, setComments] = useState<CommentItem[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [page, setPage] = useState(1);
    const [form] = Form.useForm();
    const { user, token } = useAuthStore();
    const { t, i18n } = useTranslation();
    const [replyTo, setReplyTo] = useState<CommentItem | null>(null);

    const fetchComments = useCallback(async (p: number = 1) => {
        setLoading(true);
        try {
            const res = await api(`/api/comments/${relateId}?page=${p}&size=10`);
            if (res.ok) {
                const data: PaginatedComments = await res.json();
                setComments(prev => p === 1 ? data.records : [...prev, ...data.records]);
                setTotal(data.total);
                setPage(p);
            } else {
                message.error(t('failedToFetchComments'));
            }
        } catch (error) {
            console.error('Error fetching comments:', error);
            message.error(t('networkErrorFetchingComments'));
        } finally {
            setLoading(false);
        }
    }, [relateId, t]);

    useEffect(() => {
        if (relateId) {
            fetchComments(1);
            setReplyTo(null);
            form.resetFields();
        }
    }, [relateId, fetchComments, form]);

    const updateComment = (items: CommentItem[], id: number, updater: (item: CommentItem) => CommentItem): CommentItem[] =>
        items.map(item => {
            if (item.id === id) return updater(item);
            return { ...item, replies: item.replies ? updateComment(item.replies, id, updater) : [] };
        });

    const removeComment = (items: CommentItem[], id: number): CommentItem[] =>
        items
            .filter(item => item.id !== id)
            .map(item => ({ ...item, replies: item.replies ? removeComment(item.replies, id) : [] }));

    const handleSubmit = async (values: { content: string }) => {
        if (!user || !token) {
            message.error(t('loginRequired', { defaultValue: 'Please log in first.' }));
            return;
        }

        setSubmitting(true);
        try {
            const body: Record<string, unknown> = {
                relateId,
                content: values.content,
            };
            if (replyTo) {
                body.parentId = replyTo.id;
            }

            const res = await api('/api/comments', {
                method: 'POST',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
                body: JSON.stringify(body),
            });

            if (res.ok) {
                message.success(t('commentPostedSuccessfully'));
                setReplyTo(null);
                form.resetFields();
                fetchComments(1);
            } else {
                const errorText = await res.text();
                message.error(`${t('failedToPostComment')}: ${errorText}`);
            }
        } catch (error) {
            console.error('Error posting comment:', error);
            message.error(t('networkErrorPostingComment'));
        } finally {
            setSubmitting(false);
        }
    };

    const handleUpvote = async (id: number) => {
        if (!token) {
            message.error(t('loginRequired', { defaultValue: 'Please log in first.' }));
            return;
        }
        try {
            const res = await api(`/api/comments/${id}/upvote`, {
                method: 'POST',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                const data = await res.json();
                setComments(prev => updateComment(prev, id, item => ({ ...item, upvotes: data.upvotes })));
            }
        } catch (error) {
            console.error('Upvote error:', error);
        }
    };

    const handleDelete = async (id: number) => {
        if (!token) {
            message.error(t('loginRequired', { defaultValue: 'Please log in first.' }));
            return;
        }
        try {
            const res = await api(`/api/comments/${id}`, {
                method: 'DELETE',
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            });
            if (res.ok) {
                setComments(prev => removeComment(prev, id));
                message.success(t('deletedSuccessfully', { defaultValue: 'Deleted successfully.' }));
            } else {
                message.error(t('deleteFailed', { defaultValue: 'Delete failed.' }));
            }
        } catch (error) {
            console.error('Delete comment error:', error);
            message.error(t('deleteFailed', { defaultValue: 'Delete failed.' }));
        }
    };

    const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
        const el = e.currentTarget;
        if (!loading && el.scrollHeight - el.scrollTop - el.clientHeight < 100) {
            const hasNext = page * 10 < total;
            if (hasNext) {
                fetchComments(page + 1);
            }
        }
    };

    const getDisplayUsername = (item?: Partial<CommentItem>) => {
        return item?.nickname || item?.username || 'Anonymous';
    };

    const canDelete = (item: CommentItem) => {
        return !!user && (user.role === 'ADMIN' || user.id === item.userId);
    };

    const renderActions = (item: CommentItem) => (
        <div className="flex items-center gap-0.5 sm:gap-2 shrink-0">
            <Button
                type="text"
                size="small"
                icon={item.upvotes > 0 ? <LikeFilled /> : <LikeOutlined />}
                onClick={() => handleUpvote(item.id)}
                className="text-gray-500 hover:text-blue-500 !px-1 sm:!px-2"
            >
                <span className="hidden sm:inline">{item.upvotes || 0}</span>
                <span className="sm:hidden text-xs">{item.upvotes || 0}</span>
            </Button>
            <Button
                type="text"
                size="small"
                icon={<MessageOutlined />}
                onClick={() => {
                    setReplyTo(item);
                    form.setFieldValue('content', '');
                    window.setTimeout(() => document.querySelector<HTMLTextAreaElement>('textarea.comment-editor')?.focus(), 0);
                }}
                className="text-gray-500 hover:text-blue-500 !px-1 sm:!px-2"
            >
                <span className="hidden sm:inline">{t('reply', { defaultValue: 'Reply' })}</span>
            </Button>
            {canDelete(item) ? (
                <Popconfirm
                    title={t('confirmDelete', { defaultValue: 'Delete this comment?' })}
                    onConfirm={() => handleDelete(item.id)}
                >
                    <Button type="text" size="small" danger icon={<DeleteOutlined />} className="!px-1 sm:!px-2" />
                </Popconfirm>
            ) : null}
        </div>
    );

    const renderComment = (item: CommentItem, isReply = false) => (
        <div className={`w-full ${isReply ? 'py-2' : 'py-1'}`}>
            <div className="flex items-start justify-between gap-2">
                <div className="flex items-start gap-3 min-w-0 flex-1">
                    <Avatar size={isReply ? 28 : 36} className="shrink-0 mt-0.5">
                        {getDisplayUsername(item)[0]?.toUpperCase() || 'U'}
                    </Avatar>
                    <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 flex-wrap">
                            <Text strong={!isReply} className={`dark:text-gray-200 ${isReply ? 'text-sm' : ''}`}>
                                {getDisplayUsername(item)}
                            </Text>
                            {isReply && item.replyToNickname && (
                                <Text type="secondary" className="text-xs text-blue-500">
                                    {t('replyingTo', { defaultValue: 'Replying to' })} @{item.replyToNickname}
                                </Text>
                            )}
                            <Text type="secondary" className="text-xs">
                                {formatDistanceToNow(new Date(item.createdAt), { addSuffix: true, locale: i18n.language === 'zh' || i18n.language === 'cn' ? zhCN : enUS })}
                            </Text>
                        </div>
                        <Text className={`dark:text-gray-300 whitespace-pre-wrap block mt-1 ${isReply ? 'text-sm' : ''}`}>
                            {item.content}
                        </Text>
                    </div>
                </div>
                {renderActions(item)}
            </div>
        </div>
    );

    return (
        <div className="comment-section bg-white dark:bg-zinc-800 p-3 sm:p-4 rounded-lg shadow-md max-h-[640px] overflow-y-auto" onScroll={handleScroll}>
            <Text className="text-xl font-bold mb-4 block text-gray-800 dark:text-gray-100">
                {t('comments')} ({total})
            </Text>
            <Spin spinning={loading && comments.length === 0}>
                <div className="flex flex-col gap-4">
                    {comments.length === 0 && (
                        <div className="text-center py-8 text-gray-400 dark:text-gray-500">
                            {t('noComments', { defaultValue: 'No comments yet.' })}
                        </div>
                    )}
                    {comments.map(item => (
                        <div key={item.id} className="border border-gray-100 dark:border-zinc-700 rounded-lg overflow-hidden">
                            {/* Root comment */}
                            <div className="px-4 py-3 bg-gray-50/50 dark:bg-zinc-800/80">
                                {renderComment(item)}
                            </div>
                            {/* Replies section - uniformly indented */}
                            {!!item.replies?.length && (
                                <div className="pl-6 sm:pl-8 pr-3 sm:pr-4 py-2 bg-white dark:bg-zinc-800/50 border-t border-gray-100 dark:border-zinc-700">
                                    {item.replies.map(reply => (
                                        <React.Fragment key={reply.id}>
                                            {renderComment(reply, true)}
                                        </React.Fragment>
                                    ))}
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            </Spin>

            {user ? (
                <div className="mt-6">
                    {replyTo && (
                        <Text className="text-sm text-blue-500 mb-2 block">
                            {t('replyingTo', { defaultValue: 'Replying to' })} {getDisplayUsername(replyTo)}
                            {' '}
                            <Button type="text" size="small" onClick={() => setReplyTo(null)}>{t('cancel')}</Button>
                        </Text>
                    )}
                    <Text className="text-lg font-semibold mb-2 block text-gray-800 dark:text-gray-100">{t('postComment')}</Text>
                    <Form form={form} onFinish={handleSubmit}>
                        <Form.Item name="content" rules={[{ required: true, message: t('pleaseEnterYourComment') }]}>
                            <TextArea className="comment-editor" rows={4} placeholder={replyTo ? t('writeReply', { defaultValue: 'Write a reply...' }) : t('writeYourComment')} />
                        </Form.Item>
                        <Form.Item>
                            <Button type="primary" htmlType="submit" loading={submitting}>
                                {replyTo ? t('reply', { defaultValue: 'Reply' }) : t('addComment')}
                            </Button>
                        </Form.Item>
                    </Form>
                </div>
            ) : (
                <div className="mt-6 text-center text-gray-600 dark:text-gray-400">
                    <Text>{t('loginToComment')}</Text>
                </div>
            )}
        </div>
    );
};

export default CommentSection;
