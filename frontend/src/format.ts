// Dutch time and number texts of the pages. Same output as the Python helpers they replace (overview.py).

export const DAYS_NL = ["ma", "di", "wo", "do", "vr", "za", "zo"] as const;

const pad = (n: number): string => String(n).padStart(2, "0");

interface Local {
  y: number;
  m: number;
  d: number;
  h: number;
  min: number;
  weekday: number; // 0 = ma
}

const WEEKDAY: Record<string, number> = { Mon: 0, Tue: 1, Wed: 2, Thu: 3, Fri: 4, Sat: 5, Sun: 6 };

/** The date and time of an instant in a time zone (the inbox's JOB_TIMEZONE). */
export function localParts(date: Date, timeZone: string): Local {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone, hourCycle: "h23", weekday: "short", year: "numeric", month: "numeric", day: "numeric",
      hour: "numeric", minute: "numeric",
    }).formatToParts(date).map((p) => [p.type, p.value]),
  );
  return {
    y: Number(parts.year), m: Number(parts.month), d: Number(parts.day), h: Number(parts.hour),
    min: Number(parts.minute), weekday: WEEKDAY[parts.weekday ?? ""] ?? 0,
  };
}

export function clockTime(date: Date, timeZone: string): string {
  const l = localParts(date, timeZone);
  return `${pad(l.h)}:${pad(l.min)}`;
}

/** '16-11 07:00': the short date and time of the task lists. */
export function shortDate(date: Date, timeZone: string): string {
  const l = localParts(date, timeZone);
  return `${pad(l.d)}-${pad(l.m)} ${pad(l.h)}:${pad(l.min)}`;
}

/** 'vandaag 07:00', 'morgen 07:00', 'gisteren 22:10', 'wo 07:00'; within a week, otherwise with the date. */
export function dayTime(date: Date, timeZone: string, now: Date = new Date()): string {
  const l = localParts(date, timeZone);
  const n = localParts(now, timeZone);
  const delta = Math.round((Date.UTC(l.y, l.m - 1, l.d) - Date.UTC(n.y, n.m - 1, n.d)) / 86_400_000);
  const named: Record<number, string> = { 0: "vandaag", 1: "morgen", [-1]: "gisteren" };
  const word = named[delta] ?? `${DAYS_NL[l.weekday]}${Math.abs(delta) < 7 ? "" : ` ${pad(l.d)}-${pad(l.m)}`}`;
  return `${word} ${pad(l.h)}:${pad(l.min)}`;
}

export function minutesNl(m: number): string {
  return m >= 60 ? `${Math.floor(m / 60)} u ${pad(m % 60)} min` : `${m} min`;
}

/** How long something has been running: '12 s', '5 min', '1 u 05 min'. */
export function elapsed(iso: string, now: number = Date.now()): string {
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} u ${m % 60} min`;
}

/** Weekday and date of an ISO date ('2026-11-16') as the usage bars label them: 'ma 16-11'. */
export function dayLabel(isoDate: string): { weekday: string; short: string } {
  const [y = 0, m = 1, d = 1] = isoDate.split("-").map(Number);
  const wd = (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7;
  return { weekday: DAYS_NL[wd] ?? "", short: `${DAYS_NL[wd]} ${pad(d)}-${pad(m)}` };
}
