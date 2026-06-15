import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import enTranslations from './public/locales/en/common.json';
import zhTranslations from './public/locales/zh/common.json';

const getInitialLanguage = () => {
  if (typeof window === 'undefined') return 'en';
  const saved = window.localStorage.getItem('i18nextLng');
  if (saved) return saved;
  return window.navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en';
};

i18n
  .use(initReactI18next)
  .init({
    resources: {
      en: {
        common: enTranslations,
      },
      zh: {
        common: zhTranslations,
      },
    },
    fallbackLng: 'en',
    lng: getInitialLanguage(),
    defaultNS: 'common',
    interpolation: {
      escapeValue: false,
    },
  });

if (typeof window !== 'undefined') {
  i18n.on('languageChanged', (lng) => {
    window.localStorage.setItem('i18nextLng', lng);
    document.documentElement.lang = lng === 'zh' ? 'zh-CN' : 'en';
  });
}

export default i18n;
