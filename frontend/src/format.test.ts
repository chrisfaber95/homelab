import { describe, expect, it } from "vitest";
import { clockTime, dayLabel, dayTime, elapsed, minutesNl } from "./format";

const TZ = "Europe/Amsterdam";
const now = new Date("2026-11-17T10:00:00+01:00"); // a Tuesday

describe("dayTime", () => {
  it("names today, tomorrow and yesterday", () => {
    expect(dayTime(new Date("2026-11-17T07:00:00+01:00"), TZ, now)).toBe("vandaag 07:00");
    expect(dayTime(new Date("2026-11-18T07:00:00+01:00"), TZ, now)).toBe("morgen 07:00");
    expect(dayTime(new Date("2026-11-16T22:10:00+01:00"), TZ, now)).toBe("gisteren 22:10");
  });
  it("uses the weekday within a week and the date beyond", () => {
    expect(dayTime(new Date("2026-11-19T07:00:00+01:00"), TZ, now)).toBe("do 07:00");
    expect(dayTime(new Date("2026-11-30T07:00:00+01:00"), TZ, now)).toBe("ma 30-11 07:00");
  });
  it("reads the day in the inbox time zone, not the UTC day", () => {
    expect(dayTime(new Date("2026-11-17T23:30:00Z"), TZ, now)).toBe("morgen 00:30");
  });
});

describe("small formats", () => {
  it("clockTime", () => expect(clockTime(new Date("2026-11-17T12:05:00Z"), TZ)).toBe("13:05"));
  it("minutesNl", () => {
    expect(minutesNl(45)).toBe("45 min");
    expect(minutesNl(125)).toBe("2 u 05 min");
  });
  it("elapsed", () => {
    const t = Date.parse("2026-11-17T10:00:00Z");
    expect(elapsed("2026-11-17T10:00:00Z", t + 5000)).toBe("5 s");
    expect(elapsed("2026-11-17T10:00:00Z", t + 5 * 60_000)).toBe("5 min");
    expect(elapsed("2026-11-17T10:00:00Z", t + 65 * 60_000)).toBe("1 u 5 min");
  });
  it("dayLabel", () => expect(dayLabel("2026-11-16")).toEqual({ weekday: "ma", short: "ma 16-11" }));
});
