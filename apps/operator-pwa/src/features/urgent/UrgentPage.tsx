import { useState, type FormEvent } from 'react';
import { OfflineLimits } from '../../components/OfflineLimits';
import { Screen } from '../../components/Screen';
import { t } from '../../i18n';
import { useMyTasks } from '../tasks/TasksContext';
import { useEnqueue } from '../tasks/useEnqueue';

const CATEGORIES = ['EMERGENCY', 'SAFETY', 'EQUIPMENT', 'OTHER'] as const;

/**
 * BLUEPRINT section 12, "Emergency action while offline": no promise of execution. The device may create an urgent
 * local record for sync. It is advisory: it never starts or stops anything, and the screen says so.
 */
export function UrgentPage() {
  const enqueue = useEnqueue();
  const { siteId } = useMyTasks();
  const [category, setCategory] = useState<(typeof CATEGORIES)[number]>('EMERGENCY');
  const [message, setMessage] = useState('');
  const [lastKnown, setLastKnown] = useState('');
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!message.trim() || !siteId) return;
    await enqueue({
      kind: 'URGENT_RECORD',
      siteId,
      body: {
        category,
        message: message.trim(),
        ...(lastKnown.trim() ? { last_known_instruction: lastKnown.trim() } : {}),
      },
      label: `${t('urgent.title')}: ${t(`urgent.category.${category}`)}`,
    });
    setMessage('');
    setLastKnown('');
    setSaved(true);
  }

  return (
    <Screen title={t('urgent.title')} truth="LOCAL">
      <p className="banner banner-warning m-0" data-testid="urgent-call-first">
        {t('urgent.callFirst')}
      </p>
      <p className="m-0 text-ink-700">{t('urgent.lead')}</p>
      <form className="card flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
        <div>
          <label className="label" htmlFor="urgent-cat">
            {t('urgent.category')}
          </label>
          <select
            id="urgent-cat"
            className="field"
            value={category}
            onChange={(e) => setCategory(e.target.value as (typeof CATEGORIES)[number])}
          >
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {t(`urgent.category.${c}`)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="urgent-msg">
            {t('urgent.message')}
          </label>
          <textarea
            id="urgent-msg"
            className="field"
            rows={3}
            maxLength={1000}
            value={message}
            onChange={(e) => {
              setMessage(e.target.value);
              setSaved(false);
            }}
          />
        </div>
        <div>
          <label className="label" htmlFor="urgent-last">
            {t('urgent.lastKnown')}
          </label>
          <input
            id="urgent-last"
            className="field"
            maxLength={500}
            value={lastKnown}
            onChange={(e) => setLastKnown(e.target.value)}
          />
        </div>
        <button type="submit" className="btn btn-primary" disabled={!message.trim() || !siteId}>
          {t('urgent.submit')}
        </button>
        {saved ? (
          <p className="banner m-0" role="status" data-testid="urgent-saved">
            {t('urgent.saved')}
          </p>
        ) : null}
      </form>
      <OfflineLimits open />
    </Screen>
  );
}
