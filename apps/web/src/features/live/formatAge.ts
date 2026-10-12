import { t, type MessageId } from '../../i18n';
import { ageParts } from './age';

/** "3 s", "2 min": the age text shared by the top bar, the twin header and the inspector. */
export function formatAge(ms: number): string {
  const { unit, n } = ageParts(ms);
  const id: MessageId = unit === 's' ? 'live.age.s' : unit === 'min' ? 'live.age.min' : 'live.age.h';
  return t(id, { n });
}
