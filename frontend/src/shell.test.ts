import { describe, expect, it } from "vitest";
import { localTime } from "./shell";

describe("localTime", () => {
  it("formats in the box timezone like pages.local", () => {
    expect(localTime("2026-11-16T10:03:00+00:00")).toBe("16-11 11:03");
    expect(localTime("2026-07-16T10:03:00+00:00")).toBe("16-07 12:03");
  });
  it("shows - for empty and passes garbage through", () => {
    expect(localTime("")).toBe("-");
    expect(localTime("not a date")).toBe("not a date");
  });
});
