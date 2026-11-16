import { expect, test } from "vitest";
import { greeting } from "./greeting";

test("greeting names the part", () => {
  expect(greeting("scaffold")).toBe("homelab frontend: scaffold");
});
