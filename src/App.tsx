import React from 'react';
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, theme } from 'antd';
import MainLayout from './components/MainLayout';
import MovieDetail from './pages/MovieDetail';
import Home from './pages/Home';

const App: React.FC = () => {
  return (
    <ConfigProvider
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#1677ff',
          colorBgBase: '#000000',
          colorBgContainer: '#141414',
          borderRadius: 6,
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial',
        },
        components: {
          Layout: {
            headerBg: '#141414',
            bodyBg: '#000000',
          },
          Table: {
            headerBg: '#1f1f1f',
          }
        }
      }}
    >
      <HashRouter>
        <MainLayout>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/movie/:id" element={<MovieDetail />} />
            {/* Fallback */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </MainLayout>
      </HashRouter>
    </ConfigProvider>
  );
};

export default App;