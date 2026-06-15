import React from 'react';
import { AntdRegistry } from '@ant-design/nextjs-registry';
import { App as AntdApp } from 'antd';
import { ThemeProvider } from '../components/ThemeProvider';
import { I18nProvider } from '../components/I18nProvider';
import './globals.css';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'GYING Movie',
  description: 'High Quality Movie Resources',
};

import Navbar from '../components/Navbar';

const RootLayout = ({ children }: React.PropsWithChildren) => (
  <html lang="zh-CN">
    <body className="min-h-screen bg-background text-foreground transition-colors">
      <AntdRegistry>
        <I18nProvider>
          <ThemeProvider>
              <AntdApp>
              <React.Suspense fallback={<div className="h-[64px] bg-[#141414] border-b border-[#1f1f1f]" />}>
                <Navbar />
              </React.Suspense>
              {children}
          </AntdApp>
        </ThemeProvider>
        </I18nProvider>
      </AntdRegistry>
    </body>
  </html>
);

export default RootLayout;
