import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import enTranslations from './public/locales/en/common.json';
import zhTranslations from './public/locales/zh/common.json';

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
    // The first client render must match the server's English snapshot.
    lng: 'en',
    defaultNS: 'common',
    interpolation: {
      escapeValue: false,
    },
  });

if (typeof window !== 'undefined') {
  i18n.on('languageChanged', (lng) => {
    try { window.localStorage.setItem('i18nextLng', lng); } catch { /* Storage can be unavailable. */ }
    document.documentElement.lang = lng === 'zh' ? 'zh-CN' : 'en';
  });
}

// Restore preferences only after React hydration, never during module initialization.
export function restoreClientLanguage() {
  if (typeof window === 'undefined') return;
  let preferred: string | null = null;
  try { preferred = window.localStorage.getItem('i18nextLng'); } catch { /* Use browser language. */ }
  const language = (preferred || window.navigator.language).toLowerCase().startsWith('zh') ? 'zh' : 'en';
  return i18n.changeLanguage(language);
}

export default i18n;
