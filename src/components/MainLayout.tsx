import React from 'react';
import { Layout, Input, Menu, Avatar, Button, Space } from 'antd';
import { Link } from 'react-router-dom';
import { SearchOutlined, UserOutlined, ClockCircleOutlined, StarOutlined } from '@ant-design/icons';

const { Header, Content, Footer } = Layout;

interface MainLayoutProps {
  children?: React.ReactNode;
}

const MainLayout: React.FC<MainLayoutProps> = ({ children }) => {
  const menuItems = [
    { key: 'home', label: <Link to="/">首页</Link> },
    { key: 'movies', label: '电影' },
    { key: 'tv', label: '剧集' },
    { key: 'anime', label: '动漫' },
    { key: 'hot', label: '热门' },
    { key: 'analysis', label: '解析' },
    { key: 'sites', label: '网址' },
    { key: 'settings', label: '设置' },
  ];

  return (
    <Layout className="min-h-screen">
      <Header className="sticky top-0 z-50 flex items-center justify-between px-4 md:px-8 border-b border-gray-800 h-16 w-full">
        {/* Logo Area */}
        <div className="flex items-center gap-8">
          <Link to="/" className="flex items-center gap-2 text-2xl font-bold text-blue-500 hover:text-blue-400 transition">
            <span>观影</span>
            <span className="text-white">GYING</span>
          </Link>

          {/* Desktop Menu */}
          <Menu
            theme="dark"
            mode="horizontal"
            defaultSelectedKeys={['home']}
            items={menuItems}
            className="hidden md:block min-w-[400px] border-b-0 bg-transparent text-base"
          />
        </div>

        {/* Search & Actions Area */}
        <div className="flex items-center gap-4">
          <Input 
            placeholder="请输入关键词" 
            prefix={<SearchOutlined className="text-gray-400" />}
            className="w-40 md:w-64 rounded-full bg-[#1f1f1f] border-gray-700 hover:bg-[#2a2a2a] focus:bg-[#2a2a2a] text-white"
            bordered={false}
          />
          
          <div className="hidden md:flex items-center gap-3 text-gray-400 text-sm">
            <Button type="text" icon={<StarOutlined />} className="text-gray-400 hover:text-white">
              收藏
            </Button>
            <Button type="text" icon={<ClockCircleOutlined />} className="text-gray-400 hover:text-white">
              观看历史
            </Button>
          </div>

          <Avatar 
            style={{ backgroundColor: '#1f1f1f' }} 
            icon={<UserOutlined />} 
            className="cursor-pointer hover:bg-blue-600 transition-colors"
          />
        </div>
      </Header>

      <Content className="w-full max-w-[1440px] mx-auto px-4 py-6 md:px-8">
        {children}
      </Content>

      <Footer className="text-center text-gray-500 bg-black border-t border-gray-900">
        GYING ©{new Date().getFullYear()} Created with React & Ant Design
      </Footer>
    </Layout>
  );
};

export default MainLayout;