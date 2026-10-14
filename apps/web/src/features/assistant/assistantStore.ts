import { create } from 'zustand';
import { ApiError } from '../../lib/api/client';
import { t } from '../../i18n';
import { askAssistant, type AssistantResponse } from './assistantApi';

/** Conversation state of the assistant panel (one Zustand store per feature). Memory only. */

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  response?: AssistantResponse;
  error?: string;
}

export interface AssistantState {
  messages: ChatMessage[];
  pending: boolean;
  conversationId: string | null;
  /** Site the conversation belongs to; switching sites starts a new conversation. */
  siteId: string | null;
  send: (siteId: string, text: string) => Promise<AssistantResponse | null>;
  reset: () => void;
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 503 || error.status === 502 || error.status === 504) {
      return t('assistant.error.unavailable');
    }
    return error.detail ?? error.title;
  }
  return t('assistant.error.unavailable');
}

export const useAssistantStore = create<AssistantState>()((set, get) => ({
  messages: [],
  pending: false,
  conversationId: null,
  siteId: null,

  send: async (siteId, text) => {
    const message = text.trim();
    if (!message || get().pending) return null;
    if (get().siteId !== siteId) set({ messages: [], conversationId: null, siteId });
    const user: ChatMessage = { id: globalThis.crypto.randomUUID(), role: 'user', text: message };
    set((s) => ({ messages: [...s.messages, user], pending: true }));
    try {
      const conversationId = get().conversationId;
      const response = await askAssistant({
        site_id: siteId,
        message,
        ...(conversationId ? { conversation_id: conversationId } : {}),
      });
      set((s) => ({
        pending: false,
        conversationId: response.conversation_id,
        messages: [
          ...s.messages,
          { id: response.exchange_id, role: 'assistant', text: response.answer, response },
        ],
      }));
      return response;
    } catch (error) {
      set((s) => ({
        pending: false,
        messages: [
          ...s.messages,
          { id: globalThis.crypto.randomUUID(), role: 'assistant', text: '', error: errorText(error) },
        ],
      }));
      return null;
    }
  },
  reset: () => set({ messages: [], pending: false, conversationId: null, siteId: null }),
}));
