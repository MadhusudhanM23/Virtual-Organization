import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { Bot, Loader, Send, ShieldCheck, Sparkles, TriangleAlert } from 'lucide-react';
import { DataModeBadge } from '../../components/DataModeBadge';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { cn } from '../../lib/cn';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';
import { canUse } from '../auth/permissionMatrix';
import { useTwinStore } from '../twin/twinStore';
import type { AssistantResponse, Citation } from './assistantApi';
import {
  applyDirectives,
  asDataMode,
  citationDataMode,
  citationRoute,
  flagNotes,
  providerLabel,
  splitAnswer,
} from './assistantModel';
import { useAssistantStore, type ChatMessage } from './assistantStore';

const SUGGESTIONS = [
  'assistant.suggest.bottleneck',
  'assistant.suggest.incidents',
  'assistant.suggest.kpis',
  'assistant.suggest.recommendations',
  'assistant.suggest.scenarios',
] as const;

/**
 * AI operations assistant (Plan 9.3, REQ-103/REQ-108). Answers come from services/ai with numbered citations to the
 * evidence they used, a provider/truth label, and directives that highlight and frame the evidence in the twin. The
 * assistant never approves or executes; "Suggest actions" only asks it to propose (an eligible person decides).
 */
export function AssistantPanel({ variant = 'panel' }: { variant?: 'panel' | 'page' }) {
  const siteId = useUiStore((s) => s.selectedSiteId);
  // Select a primitive: a selector that builds a new array on every call never settles under useSyncExternalStore
  // ("getSnapshot should be cached") and crashed the panel with "Maximum update depth exceeded" on the real stack.
  const canPropose = useSessionStore((s) =>
    canUse(
      (s.me?.roles ?? []).map((r) => r.role),
      'PROPOSE_RECOMMENDATION',
    ),
  );
  const messages = useAssistantStore((s) => s.messages);
  const pending = useAssistantStore((s) => s.pending);
  const send = useAssistantStore((s) => s.send);
  const reset = useAssistantStore((s) => s.reset);
  const [draft, setDraft] = useState('');

  if (!siteId) {
    return <EmptyState compact icon={Sparkles} description={t('assistant.noSite')} />;
  }

  const ask = (text: string) => {
    void send(siteId, text);
    setDraft('');
  };
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (draft.trim()) ask(draft);
  };
  const page = variant === 'page';

  return (
    <div
      className={cn('flex min-h-0 flex-col gap-3 p-4', page && 'p-0')}
      data-testid="assistant-panel"
    >
      <p className="flex items-start gap-2 text-xs text-ink-600">
        <ShieldCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent-600" />
        {t('assistant.safety')}
      </p>
      {messages.length === 0 ? (
        <div
          className="flex flex-wrap gap-2"
          role="group"
          aria-label={t('assistant.suggest.label')}
        >
          {SUGGESTIONS.map((id) => (
            <button
              key={id}
              type="button"
              onClick={() => ask(t(id))}
              disabled={pending}
              className="rounded-full border border-line px-3 py-1 text-xs font-semibold text-ink-800 hover:bg-surface-sunken disabled:opacity-60"
            >
              {t(id)}
            </button>
          ))}
        </div>
      ) : null}
      <ol
        aria-live="polite"
        aria-label={t('assistant.conversation')}
        className={cn('flex flex-col gap-3 overflow-y-auto', page ? 'max-h-[60vh]' : 'max-h-96')}
      >
        {messages.map((m) => (
          <li key={m.id}>
            <Message message={m} canPropose={canPropose} onAsk={ask} pending={pending} />
          </li>
        ))}
        {pending ? (
          <li role="status" className="flex items-center gap-2 text-sm text-ink-600">
            <Loader aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
            {t('assistant.pending')}
          </li>
        ) : null}
      </ol>
      <form onSubmit={onSubmit} className="flex items-end gap-2">
        <label className="flex min-w-0 flex-1 flex-col gap-1 text-xs font-semibold text-ink-700">
          {t('assistant.input.label')}
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                if (draft.trim()) ask(draft);
              }
            }}
            rows={2}
            maxLength={2000}
            placeholder={t('assistant.input.placeholder')}
            className="rounded-control border border-line bg-surface px-2 py-1.5 text-sm font-normal text-ink-900"
          />
        </label>
        <button
          type="submit"
          disabled={pending || !draft.trim()}
          className="flex items-center gap-1 rounded-control bg-navy-900 px-3 py-2 text-sm font-semibold text-white hover:bg-navy-800 disabled:opacity-60"
        >
          <Send aria-hidden="true" className="size-4" />
          {t('assistant.send')}
        </button>
      </form>
      {messages.length > 0 ? (
        <button
          type="button"
          onClick={reset}
          className="self-start text-xs font-semibold text-ink-600 underline-offset-2 hover:underline"
        >
          {t('assistant.newConversation')}
        </button>
      ) : null}
    </div>
  );
}

function Message({
  message,
  canPropose,
  onAsk,
  pending,
}: {
  message: ChatMessage;
  canPropose: boolean;
  onAsk: (text: string) => void;
  pending: boolean;
}) {
  if (message.role === 'user') {
    return (
      <p className="ml-6 rounded-card bg-surface-sunken px-3 py-2 text-sm text-ink-900">
        <span className="sr-only">{t('assistant.you')}: </span>
        {message.text}
      </p>
    );
  }
  if (message.error || !message.response) {
    return (
      <p role="alert" className="flex items-start gap-2 text-sm text-danger-fg">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        {message.error ?? t('assistant.error.unavailable')}
      </p>
    );
  }
  return (
    <Answer response={message.response} canPropose={canPropose} onAsk={onAsk} pending={pending} />
  );
}

function Answer({
  response,
  canPropose,
  onAsk,
  pending,
}: {
  response: AssistantResponse;
  canPropose: boolean;
  onAsk: (text: string) => void;
  pending: boolean;
}) {
  const navigate = useNavigate();
  const showAssistantHighlight = useTwinStore((s) => s.showAssistantHighlight);
  const focusPoint = useTwinStore((s) => s.focusPoint);
  const provider = providerLabel(response.provider);
  const notes = flagNotes(response);
  const index = new Map(response.citations.map((c, i) => [c.id, i + 1]));
  const hasDirectives = response.directives.length > 0;

  const showInTwin = () => {
    if (applyDirectives(response, { showAssistantHighlight, focusPoint }))
      void navigate('/twin/live');
  };

  return (
    <article
      className="flex flex-col gap-2 rounded-card border border-line px-3 py-2"
      data-testid="assistant-answer"
    >
      <header className="flex flex-wrap items-center gap-2 text-xs">
        <Bot aria-hidden="true" className="size-4 text-accent-600" />
        <span
          className="rounded-full border border-line px-2 py-0.5 font-semibold text-ink-800"
          data-testid="assistant-provider"
          title={provider.reason}
        >
          {provider.text}
        </span>
        <span className="text-ink-600">{provider.reason}</span>
        {response.data_modes.map((value) => {
          const mode = asDataMode(value);
          return mode ? <DataModeBadge key={value} dataMode={mode} /> : null;
        })}
        <span className="text-ink-600">
          {response.grounded ? t('assistant.grounded') : t('assistant.notGrounded')}
        </span>
      </header>
      <div className="flex flex-col gap-1 text-sm text-ink-900">
        {splitAnswer(response.answer).map((line, i) => (
          <p key={i}>
            {line.map((seg, j) =>
              seg.kind === 'text' ? (
                <span key={j}>{seg.text}</span>
              ) : (
                <a
                  key={j}
                  href={`#cite-${response.exchange_id}-${seg.id}`}
                  className="mx-0.5 rounded bg-accent-50 px-1 text-xs font-semibold text-accent-700"
                  aria-label={t('assistant.citation.aria', { n: index.get(seg.id) ?? seg.id })}
                >
                  {index.get(seg.id) ?? seg.id}
                </a>
              ),
            )}
          </p>
        ))}
      </div>
      {notes.map((n) => (
        <p key={n} className="flex items-start gap-1 text-xs text-warning-fg">
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          {n}
        </p>
      ))}
      {response.citations.length > 0 ? (
        <details className="text-xs" open={response.citations.length <= 4}>
          <summary className="cursor-pointer font-semibold text-ink-700">
            {t('assistant.citations', { n: response.citations.length })}
          </summary>
          <ol className="mt-1 flex flex-col gap-1">
            {response.citations.map((c, i) => (
              <CitationRow key={c.id} citation={c} n={i + 1} exchangeId={response.exchange_id} />
            ))}
          </ol>
        </details>
      ) : null}
      <div className="flex flex-wrap gap-2">
        {hasDirectives ? (
          <button
            type="button"
            onClick={showInTwin}
            className="rounded-control border border-line px-2 py-1 text-xs font-semibold hover:bg-surface-sunken"
          >
            {t('assistant.action.showInTwin')}
          </button>
        ) : null}
        {canPropose ? (
          <button
            type="button"
            disabled={pending}
            onClick={() => onAsk(t('assistant.suggest.propose'))}
            className="rounded-control border border-line px-2 py-1 text-xs font-semibold hover:bg-surface-sunken disabled:opacity-60"
          >
            {t('assistant.action.suggestActions')}
          </button>
        ) : null}
      </div>
    </article>
  );
}

function CitationRow({
  citation,
  n,
  exchangeId,
}: {
  citation: Citation;
  n: number;
  exchangeId: string;
}) {
  const mode = citationDataMode(citation);
  const route = citationRoute(citation);
  return (
    <li id={`cite-${exchangeId}-${citation.id}`} className="flex flex-wrap items-center gap-2">
      <span className="font-semibold text-ink-800">{n}.</span>
      <span className="text-ink-600">{citation.kind}</span>
      <span className="text-ink-900">{citation.label}</span>
      {mode ? <DataModeBadge dataMode={mode} /> : null}
      {route ? (
        <Link
          to={route}
          className="font-semibold text-accent-700 underline-offset-2 hover:underline"
        >
          {t('assistant.action.viewDetails')}
        </Link>
      ) : null}
    </li>
  );
}
