import { messagesEn, type MessageCatalog, type MessageId } from './messages.en';

export type { MessageId } from './messages.en';

export type MessageParams = Record<string, string | number>;

// English only for now. To add a language, register another full MessageCatalog here and set `locale`.
const catalogs: Record<string, MessageCatalog> = { en: messagesEn };

let activeLocale = 'en';

export function setLocale(locale: string): void {
  activeLocale = locale in catalogs ? locale : 'en';
}

export function getLocale(): string {
  return activeLocale;
}

/** Translate a message id, substituting `{param}` placeholders. Unknown params are left visible. */
export function t(id: MessageId, params?: MessageParams): string {
  const catalog = catalogs[activeLocale] ?? messagesEn;
  const template = catalog[id];
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}
