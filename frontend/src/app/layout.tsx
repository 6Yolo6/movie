import React from 'react';
import { AntdRegistry } from '@ant-design/nextjs-registry';
import { App as AntdApp } from 'antd';
import { ThemeProvider } from '../components/ThemeProvider';
import { I18nProvider } from '../components/I18nProvider';
import './globals.css';
import type { Metadata } from 'next';
import { SITE_TITLE, SITE_DESCRIPTION } from '../lib/site';

export const metadata: Metadata = {
  title: SITE_TITLE,
  description: SITE_DESCRIPTION,
};

import Navbar from '../components/Navbar';
import SiteTelemetry from '../components/SiteTelemetry';

const RootLayout = ({ children }: React.PropsWithChildren) => (
  <html lang="zh-CN">
    <body className="min-h-screen bg-background text-foreground transition-colors">
      <AntdRegistry>
        <I18nProvider>
          <ThemeProvider>
              <AntdApp>
              <React.Suspense fallback={<div className="h-[64px] bg-[#141414] border-b border-[#1f1f1f]" />}>
                <Navbar />
                <SiteTelemetry />
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
