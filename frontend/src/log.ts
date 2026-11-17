import { el } from "./dom";

const clock = (iso: string): string =>
  new Date(iso).toLocaleTimeString("nl-NL", { hour: "2-digit", minute: "2-digit", second: "2-digit" });

const POLL_LOG = 2000; // ms; the log runs at most ~2 s behind

interface LogData {
  lines: { at: string; kind: string; text: string }[];
  next: number;
  done: boolean;
}

/** One log panel: fetches new lines from the last one, until the run is done. */
export class LogView {
  private next = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private empty: HTMLElement | null = null;

  constructor(private readonly key: string, private readonly box: HTMLElement) {}

  start(): void {
    void this.tick();
  }

  stop(): void {
    clearTimeout(this.timer);
  }

  private async tick(): Promise<void> {
    try {
      const r = await fetch(`/api/log/${this.key}?from=${this.next}`, { cache: "no-store" });
      if (r.ok) {
        const data = (await r.json()) as LogData;
        const atBottom = this.box.scrollHeight - this.box.scrollTop - this.box.clientHeight < 24;
        for (const line of data.lines) {
          this.box.append(el("div", { class: `k-${line.kind}` },
            el("time", { datetime: line.at, text: clock(line.at) }), line.text));
        }
        if (!data.lines.length && !this.next && !this.empty) {
          this.empty = el("div", { class: "log-end", text: "Nog geen regels." });
          this.box.append(this.empty);
        }
        if (data.lines.length && this.empty) { this.empty.remove(); this.empty = null; }
        this.next = data.next;
        if (atBottom) this.box.scrollTop = this.box.scrollHeight;
        if (data.done) {
          this.box.append(el("div", { class: "log-end", text: "Klaar." }));
          return;
        }
      }
    } catch { /* box away for a moment: try again */ }
    this.timer = setTimeout(() => void this.tick(), POLL_LOG);
  }
}
