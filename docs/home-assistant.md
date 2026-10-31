# Home Assistant setup guide (H-008)

How to get the LSC/Tuya bulbs into Home Assistant (HA) with **local LAN control only**. The container, its port, the
backup and HACS are described in `docs/projects.md` ("Home Assistant (H-008)"). This page is the part you do in the UI.

Rules for this page and the repo: never the Tuya cloud integration; no device ID, local key or token in the repo
(the examples use placeholders such as `<DEVICE_ID>` and `<LOCAL_KEY>`).

## 1. Get each bulb's device ID and local key (once)

Tuya bulbs are encrypted on the LAN with a per-device **local key**. The LSC app does not show it; you read it once
from the Tuya cloud and never need the cloud again for control.

1. Make sure every bulb is paired in the **LSC Smart Connect** app (this registers it in the Tuya cloud).
2. Create a free account on <https://platform.tuya.com> (Tuya IoT Platform) and make a **Cloud project**: development
   method "Smart Home", data centre = the region of your LSC account (Western Europe for most of the EU).
3. In the project, open **Devices > Link App Account > Add App Account**, scan the QR code with the LSC app
   (Me > scan icon in the top right). Your bulbs now appear under **Devices > All Devices**.
4. Per bulb, copy the **Device ID** from that list. For the **local key**: Tuya Local can fetch it for you. When you add
   a device in HA (step 2 below) choose the cloud-assisted setup, log in with the Tuya developer/app account and pick
   the bulb; HA reads the ID and key once. Alternative: in the project open **Cloud > API Explorer > Device
   Management > Query Device Details** with the device ID; `local_key` is in the answer.
5. Write the pairs down in a password manager (not in the repo, not in a chat).

Why only once: the local key belongs to the bulb and stays the same until you **remove the bulb from the LSC app or
factory-reset it** (re-pairing generates a new key). Control after that is HA to the bulb over the LAN, so the cloud
account, the project and the app can be left alone. If you ever re-pair a bulb, repeat steps 3-4 for that bulb and
edit its entry in HA (Configure/Reconfigure).

The free Tuya developer trial for the cloud API can expire after a while (it can be renewed on the platform). That
only blocks fetching *new* keys, not the control of bulbs you already added.

## 2. Add a bulb with Tuya Local

Tuya Local is installed through HACS by `scripts/23-hacs.sh`, pinned in `config/hacs.pins` (see `docs/projects.md`
for HACS itself). Do **not** add the built-in "Tuya" integration: that is the cloud.

Before you start, give every bulb a **fixed IP**: a DHCP reservation for the bulb's MAC address in the router.
Local control needs a stable address, and the box's Docker bridge cannot discover bulbs by broadcast, so you type the
IP in yourself.

1. Open HA over Tailscale (`http://homelab:8110`, or the phone app).
2. **Settings > Devices & services > Add integration > Tuya Local**.
3. Choose the cloud-assisted setup (or "manual" if you wrote down the ID and key), enter the bulb's **IP address**,
   **Device ID** and **Local key**, and the **protocol version** (Auto; 3.3, 3.4 and 3.5 are common).
4. Pick the matching device type from the list (for LSC bulbs usually a colour RGBCW light) and give the entity a
   clear name, e.g. `Living room lamp` (entity `light.living_room_lamp`). The f1-dashboard uses these names.
5. Check on the device page: on/off, brightness and colour work.

If Tuya Local does **not** support a bulb model, or only with visible lag: **stop and report it**. The builder does
not work around it with the cloud integration or LocalTuya; Chris decides what happens (replace the bulb, another
local integration, or accept the lag). Note the model, the Tuya product ID and what failed in the brief or inbox task.

## 3. Scenes and automations

Both are made in the UI and need no file edits:

- **Scene**: Settings > Automations & scenes > Scenes > Create scene. Pick the lights, set each to the colour and
  brightness you want (the live preview changes the bulbs), name it, save.
- **Automation**: Settings > Automations & scenes > Automations > Create automation. Pick a trigger (for example a time
  or sunset), an action (for example *Scene: activate*) and save.

They are stored in the config volume `/srv/homelab/data/homeassistant/config` (`scenes.yaml`, `automations.yaml`,
`.storage/`), outside the repo and the releases, so they survive a container restart, a redeploy and a reboot. That
volume is in `config/backup.list`. Check once: create a scene and an automation, restart HA (Settings > System >
Restart), and see that both are still there.

## 4. Add other devices later

Nothing on the box changes. Settings > Devices & services > Add integration, and add the device:

- Another Tuya bulb or plug: repeat sections 1 and 2 (fixed IP, ID, key).
- Devices reachable by unicast IP (most Wi-Fi devices with a local API): add them with their IP by hand.
- Devices that need broadcast/mDNS discovery (Chromecast, HomeKit, some Hue setups) are not discovered from the
  bridge network; enter their address manually where the integration allows it. Zigbee/Bluetooth dongles need a
  device passed into the container: that is a change to `compose.yaml` in a PR (and `exposure-checker`).
- An integration from HACS: add it to `config/hacs.pins` (version, URL, sha256) in a PR, never update from inside HACS.

## Measurements

Fill in after the bulbs are added. Measure with the container idle for a few minutes and the local LLM loaded, so the
number says whether it fits next to it.

| What | How | Target | Result | Date |
|---|---|---|---|---|
| Idle memory of the `homeassistant` container | `bash scripts/ha-memory.sh` | fits next to the LLM (note `free -h`) | _TBD_ | _TBD_ |
| Colour change, API call until HA has confirmed | `bash scripts/ha-colour-latency.sh light.<name> 255 0 0` (3 runs) | < 1 s | _TBD_ | _TBD_ |
| Colour change, seen on the bulb (phone over Tailscale, UI) | slow-motion video or stopwatch, 3 runs | < 1 s | _TBD_ | _TBD_ |

`scripts/ha-colour-latency.sh` reads a long-lived token (HA profile page > Security > Long-lived access tokens) from
`/etc/homelab/ha-token` (override: `HA_TOKEN_FILE`), passes it to curl on stdin and never prints it. Create that file
with `sudo install -m 600 /dev/stdin /etc/homelab/ha-token`, paste, Ctrl-D (make it readable for whoever runs the
script, or for the f1-dashboard's user). The API time does not include the bulb itself, which is why the third row
exists. `scripts/ha-memory.sh` needs access to the rootless Docker of `deploy` (set `DOCKER_HOST` or run it as that
user).
