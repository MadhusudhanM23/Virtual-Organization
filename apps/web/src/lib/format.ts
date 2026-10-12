/**
 * Locale-, time-zone-, currency- and unit-aware formatting (BLUEPRINT §5, §15, NFR-12).
 *
 * Storage and transport are always UTC ISO-8601 and SI units; everything shown to a user is formatted
 * here from the *selected site's* configuration. Nothing in this module assumes a country: locale,
 * time zone, currency and unit system are always explicit inputs.
 */

export type UnitSystem = 'metric' | 'imperial';

export interface DateTimeOptions {
  /** IANA time zone of the selected site/organization, e.g. "Asia/Kolkata". */
  timeZone: string;
  /** BCP 47 locale, e.g. "en-IN". */
  locale: string;
}

/** Parses an ISO-8601 instant; throws instead of silently rendering "Invalid Date". */
function parseInstant(isoUtc: string): Date {
  const date = new Date(isoUtc);
  if (Number.isNaN(date.getTime())) {
    throw new RangeError(`Invalid ISO-8601 timestamp: ${isoUtc}`);
  }
  return date;
}

/** Formats a UTC instant in the site's time zone, e.g. "09 Oct 2026, 15:54 GMT+5:30". */
export function formatDateTime(isoUtc: string, { timeZone, locale }: DateTimeOptions): string {
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZone,
    timeZoneName: 'short',
  }).format(parseInstant(isoUtc));
}

/** Like {@link formatDateTime} with seconds, e.g. "12 Oct 2026, 15:04:07 GMT+5:30" (replay time, event times). */
export function formatDateTimeSeconds(isoUtc: string, { timeZone, locale }: DateTimeOptions): string {
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone,
    timeZoneName: 'short',
  }).format(parseInstant(isoUtc));
}

/** Time of day with seconds in the site's time zone, e.g. "15:04:07" (compact lists). */
export function formatTimeSeconds(isoUtc: string, { timeZone, locale }: DateTimeOptions): string {
  return new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone,
  }).format(parseInstant(isoUtc));
}

export function formatNumber(
  value: number,
  locale: string,
  options: Intl.NumberFormatOptions = {},
): string {
  return new Intl.NumberFormat(locale, options).format(value);
}

/** `currency` is an ISO 4217 code ("INR", "EUR"); grouping and symbol placement follow `locale`. */
export function formatCurrency(amount: number, currency: string, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount);
}

const FEET_PER_METER = 3.280839895;
const METERS_PER_MILE = 1609.344;

/**
 * Formats a distance given in meters (the storage unit) in the site's unit system. Short distances use
 * meters/feet, long ones kilometers/miles. `locale` is optional here because digits and separators are
 * the only locale-dependent part; pass it whenever the site has one.
 */
export function formatDistance(meters: number, units: UnitSystem, locale?: string): string {
  const format = (value: number, unit: string, maximumFractionDigits: number) =>
    new Intl.NumberFormat(locale, {
      style: 'unit',
      unit,
      unitDisplay: 'short',
      maximumFractionDigits,
    }).format(value);

  if (units === 'metric') {
    return Math.abs(meters) >= 1000
      ? format(meters / 1000, 'kilometer', 1)
      : format(meters, 'meter', 0);
  }
  return Math.abs(meters) >= METERS_PER_MILE
    ? format(meters / METERS_PER_MILE, 'mile', 1)
    : format(meters * FEET_PER_METER, 'foot', 0);
}
