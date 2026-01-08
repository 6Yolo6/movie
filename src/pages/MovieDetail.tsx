import React, { useState, useEffect } from 'react';
import { 
  Row, Col, Typography, Tag, Button, Tabs, Table, Radio, Space, 
  Descriptions, message, Input, Spin, Modal, Form, Select, Empty 
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { 
  HeartOutlined, QrcodeOutlined, EditOutlined, DeleteOutlined, 
  LoadingOutlined, PlusOutlined 
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';

import { ResourceType, Provider, ResourceLink, Movie } from '../types';
import { api } from '../services/mockApi';

const { Title, Paragraph, Text } = Typography;
const { Option } = Select;

// Helper component for Rating Box
const RatingBox: React.FC<{
  source: string;
  rating: string | number;
  subText: string;
  color?: string;
}> = ({ source, rating, subText, color = 'text-orange-500' }) => (
  <div className="bg-[#1f1f1f] rounded-lg p-3 flex justify-between items-center mb-2">
    <div>
      <div className={`text-2xl font-bold ${color}`}>{rating}</div>
      <div className="text-xs text-gray-500">{subText}</div>
    </div>
    <div className="text-gray-400 font-medium text-sm">{source}</div>
  </div>
);

const MovieDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  
  // Data State
  const [movie, setMovie] = useState<Movie | null>(null);
  const [resources, setResources] = useState<ResourceLink[]>([]);
  const [loading, setLoading] = useState(true);
  
  // UI State
  const [activeTab, setActiveTab] = useState<string>('drive');
  const [providerFilter, setProviderFilter] = useState<string>('ALL');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // Fetch Data
  const fetchData = async () => {
    if (!id) return;
    try {
      const [movieData, resourceData] = await Promise.all([
        api.getMovieById(id),
        api.getResourcesByMovieId(id)
      ]);
      
      if (!movieData) {
        message.error('未找到该电影');
        navigate('/');
        return;
      }
      setMovie(movieData);
      setResources(resourceData);
    } catch (e) {
      console.error(e);
      message.error('加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [id]);

  // Handle Add Resource
  const handleAddResource = async (values: any) => {
    if (!id) return;
    setSubmitting(true);
    try {
      await api.addResource({
        movieId: id,
        type: values.type,
        provider: values.provider,
        title: values.title,
        url: values.url,
        code: values.code,
        size: '未知大小', // Simplification for demo
      });
      message.success('发布成功！');
      setIsModalOpen(false);
      form.resetFields();
      // Refresh resources
      const updatedResources = await api.getResourcesByMovieId(id);
      setResources(updatedResources);
    } catch (error) {
      message.error('发布失败');
    } finally {
      setSubmitting(false);
    }
  };

  // Handle Delete Resource
  const handleDeleteResource = async (resourceId: string) => {
    Modal.confirm({
      title: '确认删除?',
      content: '删除后无法恢复',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await api.deleteResource(resourceId);
          message.success('删除成功');
          setResources(prev => prev.filter(r => r.id !== resourceId));
        } catch (e) {
          message.error('删除失败');
        }
      }
    });
  };

  // Filter Logic
  const getFilteredResources = (type: ResourceType) => {
    let list = resources.filter(r => r.type === type);
    if (type === ResourceType.DRIVE && providerFilter !== 'ALL') {
      list = list.filter(r => r.provider === providerFilter);
    }
    return list;
  };

  const driveResources = getFilteredResources(ResourceType.DRIVE);
  const magnetResources = getFilteredResources(ResourceType.MAGNET);
  
  // Table Columns Definition
  const columns: ColumnsType<ResourceLink> = [
    {
      title: '名称',
      dataIndex: 'title',
      key: 'title',
      width: '40%',
      render: (text: string, record: ResourceLink) => (
        <div className="flex items-center gap-2">
           <Tag color={record.provider === Provider.XUNLEI ? 'blue' : (record.provider === Provider.BAIDU ? 'green' : 'purple')}>
             {record.type === ResourceType.MAGNET ? '磁力' : '网盘'}
           </Tag>
           <a href={record.url} target="_blank" rel="noreferrer" className="text-blue-400 hover:text-blue-300 font-medium truncate max-w-[200px] md:max-w-[300px] block" title={text}>
             {text}
           </a>
        </div>
      )
    },
    {
      title: '提取码',
      dataIndex: 'code',
      key: 'code',
      width: '15%',
      render: (code: string) => code ? (
        <Text copyable={{ text: code }} className="text-gray-400">
           {code}
        </Text>
      ) : <span className="text-gray-600">-</span>
    },
    {
      title: '发布者',
      dataIndex: ['uploader', 'username'],
      key: 'uploader',
      responsive: ['md'],
      render: (text: string) => <span className="text-gray-400">{text}</span>
    },
    {
      title: '更新时间',
      dataIndex: 'createTime',
      key: 'createTime',
      responsive: ['md'],
      render: (text: string) => <span className="text-gray-500 text-sm">{text}</span>
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: ResourceLink) => (
        <Space size="middle">
          <Button type="link" size="small" icon={<EditOutlined />} className="text-gray-500 hover:text-blue-400 p-0">编辑</Button>
          <Button 
            type="link" 
            size="small" 
            danger 
            icon={<DeleteOutlined />} 
            className="p-0"
            onClick={() => handleDeleteResource(record.id)}
          >
            删除
          </Button>
        </Space>
      )
    }
  ];

  if (loading) return <div className="flex justify-center h-[50vh] items-center"><Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} /></div>;
  if (!movie) return <Empty />;

  return (
    <div className="animate-fade-in">
      {/* --- HERO SECTION --- */}
      <div className="mb-8">
        <Row gutter={32}>
          {/* Left: Poster */}
          <Col xs={24} md={6}>
            <div className="relative group">
               <img 
                 src={movie.posterUrl} 
                 alt={movie.titleCn} 
                 className="w-full rounded-lg shadow-2xl shadow-blue-900/20 object-cover aspect-[2/3]"
               />
               <div className="absolute top-2 left-2">
                 <Tag color="blue" className="!mr-0">最后更新于 2天前</Tag>
               </div>
            </div>
            <div className="mt-4 flex gap-2">
              <Button type="primary" block icon={<HeartOutlined />} className="bg-blue-600 hover:bg-blue-500 h-10">
                添加收藏
              </Button>
              <Button icon={<QrcodeOutlined />} className="h-10 w-10 flex justify-center items-center" />
            </div>
          </Col>

          {/* Middle: Info */}
          <Col xs={24} md={12} className="mt-6 md:mt-0">
            <Title level={1} className="!text-white !mb-2">
              {movie.titleCn} <span className="text-2xl text-gray-400 font-normal">{movie.titleEn} ({movie.year})</span>
            </Title>
            
            <div className="space-y-4 text-sm text-gray-300 mt-6">
              <Descriptions column={1} labelStyle={{ color: '#8c8c8c' }} contentStyle={{ color: '#d9d9d9' }}>
                 <Descriptions.Item label="导演">
                    <Space>{movie.directors.map(d => <a key={d} className="text-blue-400">{d}</a>)}</Space>
                 </Descriptions.Item>
                 <Descriptions.Item label="编剧">
                    <Space>{movie.writers.map(w => <span key={w}>{w}</span>)}</Space>
                 </Descriptions.Item>
                 <Descriptions.Item label="主演">
                    <Paragraph className="!text-gray-300 !mb-0" ellipsis={{ rows: 2, expandable: true, symbol: '更多' }}>
                      {movie.actors.join(' / ')}
                    </Paragraph>
                 </Descriptions.Item>
                 <Descriptions.Item label="类型">
                    <Space>{movie.tags.map(t => <span key={t}>{t}</span>)}</Space>
                 </Descriptions.Item>
                 <Descriptions.Item label="地区">
                    {movie.area.join(' / ')}
                 </Descriptions.Item>
                 <Descriptions.Item label="语言">
                    {movie.language}
                 </Descriptions.Item>
                 <Descriptions.Item label="上映">
                    {movie.releaseDate}
                 </Descriptions.Item>
                 <Descriptions.Item label="片长">
                    {movie.duration}
                 </Descriptions.Item>
                 <Descriptions.Item label="别名">
                    {movie.aliases.join(' / ')}
                 </Descriptions.Item>
              </Descriptions>
            </div>
          </Col>

          {/* Right: Ratings */}
          <Col xs={24} md={6} className="mt-6 md:mt-0">
            <RatingBox source="豆瓣" rating={movie.ratingDouban} subText={`${movie.voteCount}人评分`} />
            <RatingBox source="IMDb" rating={movie.ratingImdb} subText="52395人评分" />
            <RatingBox source="烂番茄" rating={`${movie.ratingTomatoes}%`} subText="260人评分" color="text-red-500" />
            <RatingBox source="观众" rating={`${movie.ratingAudience}%`} subText="13823人评分" color="text-red-500" />
          </Col>
        </Row>
      </div>

      {/* --- SUMMARY SECTION --- */}
      <div className="mb-8">
        <Title level={3} className="!text-gray-200">电影简介</Title>
        <Paragraph className="!text-gray-400 text-base leading-7" ellipsis={{ rows: 3, expandable: true, symbol: '[展开全部]' }}>
          {movie.summary}
        </Paragraph>
      </div>

      {/* --- RESOURCES SECTION --- */}
      <div className="bg-[#141414] rounded-xl border border-gray-800 p-6">
        <Tabs 
          activeKey={activeTab} 
          onChange={setActiveTab}
          type="card"
          items={[
            { 
              key: 'magnet', 
              label: `磁力资源 (${magnetResources.length})`,
              children: (
                <div className="mt-4">
                   <div className="flex justify-end mb-4">
                      <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => setIsModalOpen(true)}>发布磁力</Button>
                   </div>
                  <Table 
                    dataSource={magnetResources} 
                    columns={columns} 
                    rowKey="id" 
                    pagination={false} 
                    className="ant-table-dark"
                  />
                </div>
              )
            },
            { 
              key: 'drive', 
              label: `网盘资源 (${driveResources.length})`,
              children: (
                <div className="mt-4">
                  <div className="flex justify-between items-center mb-4 flex-wrap gap-4">
                    <div className="flex items-center gap-4">
                       <Title level={4} className="!text-white !mb-0">网盘下载</Title>
                       <Button 
                         type="primary" 
                         size="small" 
                         className="bg-blue-600" 
                         icon={<PlusOutlined />}
                         onClick={() => setIsModalOpen(true)}
                       >
                         发布资源
                       </Button>
                    </div>
                    
                    <div className="flex gap-4">
                       <Radio.Group 
                         value={providerFilter} 
                         onChange={e => setProviderFilter(e.target.value)}
                         buttonStyle="solid"
                         className="bg-[#1f1f1f] p-1 rounded-md"
                       >
                         <Radio.Button value="ALL" className="!bg-transparent !border-0 text-gray-400 hover:text-white">全部 {resources.filter(r => r.type === ResourceType.DRIVE).length}</Radio.Button>
                         <Radio.Button value={Provider.XUNLEI} className="!bg-transparent !border-0 text-gray-400 hover:text-white">迅雷</Radio.Button>
                         <Radio.Button value={Provider.BAIDU} className="!bg-transparent !border-0 text-gray-400 hover:text-white">百度</Radio.Button>
                         <Radio.Button value={Provider.QUARK} className="!bg-transparent !border-0 text-gray-400 hover:text-white">夸克</Radio.Button>
                       </Radio.Group>
                       <Input.Search placeholder="输入关键词" className="w-48" />
                    </div>
                  </div>

                  <Table 
                    dataSource={driveResources} 
                    columns={columns} 
                    rowKey="id" 
                    pagination={false}
                  />
                </div>
              )
            },
            { 
              key: 'online', 
              label: '在线播放 (0)',
              children: <div className="p-8 text-center text-gray-500">暂无在线播放资源</div>
            }
          ]}
        />
      </div>

      {/* Add Resource Modal */}
      <Modal
        title="发布新资源"
        open={isModalOpen}
        onCancel={() => setIsModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleAddResource}
          initialValues={{
            type: activeTab === 'magnet' ? ResourceType.MAGNET : ResourceType.DRIVE,
            provider: Provider.NONE
          }}
        >
          <Form.Item name="type" label="资源类型" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value={ResourceType.DRIVE}>网盘</Radio>
              <Radio value={ResourceType.MAGNET}>磁力</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item 
            noStyle 
            shouldUpdate={(prev, curr) => prev.type !== curr.type}
          >
            {({ getFieldValue }) => 
               getFieldValue('type') === ResourceType.DRIVE ? (
                <Form.Item name="provider" label="网盘服务商" rules={[{ required: true }]}>
                  <Select>
                    <Option value={Provider.BAIDU}>百度网盘</Option>
                    <Option value={Provider.QUARK}>夸克网盘</Option>
                    <Option value={Provider.XUNLEI}>迅雷云盘</Option>
                    <Option value={Provider.ALIYUN}>阿里云盘</Option>
                  </Select>
                </Form.Item>
               ) : null
            }
          </Form.Item>

          <Form.Item name="title" label="资源标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="例如：Predator.2025.1080p.mp4" />
          </Form.Item>

          <Form.Item name="url" label="链接地址" rules={[{ required: true, message: '请输入链接' }]}>
            <Input placeholder="https://..." />
          </Form.Item>

          <Form.Item name="code" label="提取码 (可选)">
            <Input placeholder="例如：a1b2" style={{ width: 120 }} />
          </Form.Item>

          <div className="flex justify-end gap-2 mt-6">
            <Button onClick={() => setIsModalOpen(false)}>取消</Button>
            <Button type="primary" htmlType="submit" loading={submitting}>提交发布</Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
};

export default MovieDetail;