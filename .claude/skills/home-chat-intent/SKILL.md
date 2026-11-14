---
name: home-chat-intent
description: "How the local model turns a plain chat message into one allowed home action (H-019): the actions it may choose from, that it asks when unsure and never guesses, and the test phrases the build must pass. Use when building or changing the home-command interpretation in the conversation, its prompt, or its tests."
---

# Home chat intent: free text to one allowed action

Written for brief H-019 (`homelab/briefs/H-019-home-assistant-front.md` in the `product` repo). The brief is the
source. It amends H-016's rule "no model classifies free text or acts on its own" for home actions only (Chris's
choice of 2026-11-13); that rule still stands for tasks and approvals. Facts the brief does not give (model name,
prompt wording, output format) are for the build to decide and record, not for this skill to invent.

## What happens to a plain message
- A plain message in the conversation, from kitt or Discord, that the local model reads as a home command is carried
  out, and the reply says exactly what was done to which items.
- Any other plain message is answered from private search, as in H-016.
- Only the local model interprets. No message is sent to Claude for this.
- With the local model unavailable, the reply says home commands are off for now, and nothing changes.

## What the model may choose
- Only from the allowed actions on ticked items: on, off, brightness, colour, start a scene, automation on, off or run.
  Nothing else can result from a message. See `home-actions` for the picked list and the per-client permission, which
  are checked after the model, on the box, and refuse whatever the model chose.
- The model gets the ticked items (name, kind, area) as its choices and returns one action on one of them, or "ask",
  or "not a home command". Validate that output; anything that does not parse or names an unknown item or action
  becomes "ask" or "not a home command", never an action.

## Unsure means ask
- When the message is unclear or matches more than one item, the reply asks which one and nothing changes.
- Never guess an item, an action or a value.

## Questions are not commands
- A question that only mentions lights or the home changes nothing and goes to private search, for example
  "why did the lights brief get parked?".

## Test phrases (the build must pass a recorded set)
- Keep the set in the repo as a fixture, run by the tests. It covers home commands in English and Dutch that are
  carried out correctly (for example "lights red", "movie scene"), ambiguous messages that must ask, and questions
  that only mention the home and must change nothing.
- Add a phrase whenever a wrong result is found.

## Delay
- Reading every plain message with the local model first delays search answers. The build measures the delay and
  reports it. If it is more than about 2 seconds, Chris decides whether home commands need a leading word such as
  "home"; do not add one on your own.
