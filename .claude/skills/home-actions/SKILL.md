---
name: home-actions
description: "Rules for acting on the home through Home Assistant (H-019): only items on Chris's picked list, the fixed set of allowed actions, each client's permission and off-switch, where the Home Assistant token lives, and what is logged. Use for any task that touches the home endpoints, the picked list, the edit mode, the home card or the home permissions on the box."
---

# Home actions: the rules on the box

Written for brief H-019 (`homelab/briefs/H-019-home-assistant-front.md` in the `product` repo). The brief is the
source; when this skill and it disagree, the brief wins. Setup of Home Assistant itself is in `docs/home-assistant.md`
and `docs/projects.md` ("Home Assistant (H-008)"). Facts the brief does not give are open questions: do not invent them.

## The picked list
- Chris picks what the clients may use. Edit mode lists everything Home Assistant knows of the kinds light, switch,
  scene, automation and sensor, by area, with a tick per item and a second tick for "on the card". It is offered on
  kitt's Home screen and on the homelab page.
- Only ticked items appear in any client and can be acted on. An action on an unticked item is refused and logged,
  whichever client sent it.
- The box keeps the list, not kitt: after kitt's service is wiped and redeployed, Home shows the same items.
- A ticked item that Home Assistant no longer knows shows as missing in edit mode and nowhere else.
- Locks, alarm panels, garage doors, covers and cameras cannot be ticked.

## The allowed actions (nothing else exists)
- Light or switch: on, off. Light only: brightness and colour, where the light supports them.
- Scene: start.
- Automation: on, off, run once.
- Sensor: read only.
- Creating or editing scenes, automations, areas or devices stays in Home Assistant's own page. Do not add actions.
- No confirm is asked for any home action from any client, Discord included (Chris's choice of 2026-11-13). The
  per-client switch is the brake.

## Permissions per client
- kitt, the homelab page and Discord each have a home permission in the homelab repo's config, and a switch on the
  homelab page that works without a restart.
- With the permission or switch off, that client still shows the state but its controls say home control is off; an
  action from it is refused and logged.
- The permission is enforced on the box on every request, never trusted from the client.

## The Home Assistant token
- The token for this feature is its own, not the f1-dashboard's.
- It lives only in `/etc/homelab` (mode 600, as `docs/home-assistant.md` does for the other token) and reaches no
  client, repo, log or conversation. Pass it to curl or the HTTP client without printing it.
- Home Assistant gets no new way in: no new listening port, and the exposure checker (`bin/homelab-exposure`) passes.
- The docs must say how to create the token, how to pick items, and how to switch home control off per client.

## Logging
- Every home action is logged with its time, client, item and result, including refused ones.
- Actions from chat also appear in the conversation (H-016), and the reply says exactly what was done to which items.

## Other rules from the brief
- If the f1-dashboard (H-007) and this feature both set a light, the last one wins; do not change how the f1-dashboard
  drives the lights.
- The card and edit mode on the homelab page work without kitt and without Discord being built or running.
- With Home Assistant stopped, clients say it is unreachable and nothing else on the box breaks.
