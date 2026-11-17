import { describe, expect, it } from "vitest";
import { citeParts, safeHref } from "./search";

describe("safeHref", () => {
  it("allows web and obsidian links only", () => {
    expect(safeHref("https://example.org/x")).toBe("https://example.org/x");
    expect(safeHref("obsidian://open?vault=a")).toBe("obsidian://open?vault=a");
    expect(safeHref("javascript:alert(1)")).toBeNull();
    expect(safeHref(undefined)).toBeNull();
  });
});

describe("citeParts", () => {
  it("links [n] to the source, or to its place in the list without a link; unknown [n] stays text", () => {
    const parts = citeParts({ answer: "A [1] B [2] C [3]", citations: [{ n: 1, location: "x", link: "https://e.org" }, { n: 2, location: "y", link: "javascript:x" }] });
    expect(parts.map((p) => p.text).join("")).toBe("A [1] B [2] C [3]");
    expect(parts.filter((p) => p.cite !== undefined).map((p) => [p.text, p.href])).toEqual([["[1]", "https://e.org"], ["[2]", "#cite-2"]]);
  });
});
