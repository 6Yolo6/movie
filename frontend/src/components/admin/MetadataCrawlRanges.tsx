'use client';

import { Button, Col, Form, InputNumber, Row, Space, Tag, Typography } from 'antd';
import { PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

type Props = {
    provider: 'tmdb' | 'gying';
    options: { value: string; label: string }[];
    pausedSources?: Record<string, boolean>;
    pausingSource?: string | null;
    pauseDisabled?: boolean;
    onPauseChange?: (source: string, paused: boolean) => void;
};

export default function MetadataCrawlRanges({ provider, options, pausedSources, pausingSource,
    pauseDisabled, onPauseChange }: Props) {
    const { t } = useTranslation();
    const sources: string[] = Form.useWatch(`${provider}AutoSyncSources`) || [];
    const field = `${provider}AutoSyncRanges`;
    return <div data-testid={`${provider}-crawl-ranges`}>
        <Typography.Text type="secondary">{t('resourceHubCrawlRangesHelp')}</Typography.Text>
        {provider === 'gying' && onPauseChange && <Typography.Paragraph type="secondary" className="!mb-0 mt-2">
            {t('resourceHubGyingPauseHelp')}
        </Typography.Paragraph>}
        {options.filter(option => sources.includes(option.value)).map(option => (
            <Row key={option.value} gutter={[12, 0]} align="middle" className="mt-3">
                <Col xs={24} md={8}>
                    <Space orientation="vertical" size={4} className="mb-2 w-full md:mb-0">
                        <Typography.Text>{option.label}</Typography.Text>
                        {provider === 'gying' && onPauseChange && <Space wrap size={6}>
                            <Tag color={pausedSources?.[option.value] ? 'default' : 'green'}
                                data-testid={`gying-pause-state-${option.value}`}>
                                {t(pausedSources?.[option.value] ? 'resourceHubGyingSourcePaused' : 'resourceHubGyingSourceNotPaused')}
                            </Tag>
                            <Button size="small"
                                data-testid={`gying-pause-${option.value}`}
                                aria-label={`${option.label} ${t(pausedSources?.[option.value] ? 'resourceHubGyingResume' : 'resourceHubGyingPause')}`}
                                icon={pausedSources?.[option.value] ? <PlayCircleOutlined /> : <PauseCircleOutlined />}
                                loading={pausingSource === option.value}
                                disabled={Boolean(pausingSource) || pauseDisabled}
                                onClick={() => onPauseChange(option.value, !pausedSources?.[option.value])}>
                                {t(pausedSources?.[option.value] ? 'resourceHubGyingResume' : 'resourceHubGyingPause')}
                            </Button>
                        </Space>}
                    </Space>
                </Col>
                <Col xs={12} md={8}>
                    <Form.Item name={[field, option.value, 'startPage']} label={t('resourceHubCrawlStartPage')}
                        rules={[{ required: true, type: 'integer', min: 1, max: 500, message: t('resourceHubCrawlRangeInvalid') }]}>
                        <InputNumber min={1} max={500} precision={0} className="w-full"
                            aria-label={`${option.label} ${t('resourceHubCrawlStartPage')}`} />
                    </Form.Item>
                </Col>
                <Col xs={12} md={8}>
                    <Form.Item name={[field, option.value, 'endPage']} label={t('resourceHubCrawlEndPage')}
                        dependencies={[[field, option.value, 'startPage']]}
                        rules={[
                            { required: true, type: 'integer', min: 1, max: 500, message: t('resourceHubCrawlRangeInvalid') },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    return value >= getFieldValue([field, option.value, 'startPage'])
                                        ? Promise.resolve()
                                        : Promise.reject(new Error(t('resourceHubCrawlRangeInvalid')));
                                },
                            }),
                        ]}>
                        <InputNumber min={1} max={500} precision={0} className="w-full"
                            aria-label={`${option.label} ${t('resourceHubCrawlEndPage')}`} />
                    </Form.Item>
                </Col>
            </Row>
        ))}
    </div>;
}
