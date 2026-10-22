'use client';

import { Col, Form, InputNumber, Row, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

type Props = {
    provider: 'tmdb' | 'gying';
    options: { value: string; label: string }[];
};

export default function MetadataCrawlRanges({ provider, options }: Props) {
    const { t } = useTranslation();
    const sources: string[] = Form.useWatch(`${provider}AutoSyncSources`) || [];
    const field = `${provider}AutoSyncRanges`;
    return <div data-testid={`${provider}-crawl-ranges`}>
        <Typography.Text type="secondary">{t('resourceHubCrawlRangesHelp')}</Typography.Text>
        {options.filter(option => sources.includes(option.value)).map(option => (
            <Row key={option.value} gutter={[12, 0]} align="middle" className="mt-3">
                <Col xs={24} md={8}><Typography.Text>{option.label}</Typography.Text></Col>
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
