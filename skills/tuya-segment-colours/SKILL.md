---
name: tuya-segment-colours
description: Set separate colours on segments of a Tuya LED strip over the LAN using DP 61 (paint_colour_data), and capture and decode the command on a strip whose format is not known yet. Use for any work on per-segment colour, painting or addressable effects on Tuya, LSC or Eurodomest strips, including the f1-dashboard lights. Not for whole-strip colour on one bulb or strip (Home Assistant's light.turn_on or DP 24 does that).
---

# Tuya segment colours

Home Assistant's `light.turn_on` sets one colour for a whole light. The segment command has to go directly to the strip
over the LAN, on the strip's local Tuya connection.

Verified on 2026-11-01 on one "Eurodomest Smart LED Strip Kit" from Action (Tuya category `dd`, protocol 3.5, 20 segments).
On any other strip, treat the format below as a first guess and capture it first.

## Secrets

Device IDs, IPs and local keys never go in a repo, a log, an error message, a PR, a chat or this skill. Read them from a
file outside the repo that only the service account can read. Print DP values only: they hold no secrets. If you need a
key and don't have one, stop and ask Chris; don't search the box for one.

The local key changes when the strip is removed and re-added in the app. Never do that as part of a task.

## DP 61 format

The value is a base64 string. Decoded, big-endian:

| Bytes | Meaning |
|---|---|
| `00 01` | Header, the same in every capture |
| `00 14` | 20; probably the segment count |
| 1 byte | `00` whole strip, `01` segments |
| 2 bytes | Hue, 0-360 |
| 2 bytes | Saturation, 0-1000 |
| 2 bytes | Brightness, 0-1000 |
| `81 NN` | Segment number, 1-20; only when the mode byte is `01` |

Example: segment 5 green at brightness 761 is `00 01 00 14 01 00 78 03 e8 02 f9 81 05`, sent as `AAEAFAEAeAPoAvmBBQ==`.

```ts
/** DP 61 value for one segment (1-20), or for the whole strip when segment is omitted. */
export function paintValue(h: number, s: number, v: number, segment?: number): string {
  const b = Buffer.alloc(segment === undefined ? 11 : 13);
  b.writeUInt16BE(1, 0);
  b.writeUInt16BE(20, 2);
  b[4] = segment === undefined ? 0 : 1;
  b.writeUInt16BE(h, 5);
  b.writeUInt16BE(s, 7);
  b.writeUInt16BE(v, 9);
  if (segment !== undefined) {
    b[11] = 0x81;
    b[12] = segment;
  }
  return b.toString('base64');
}
```

## What is verified and what isn't

**Verified by eye on the strip:**
- A captured value, replayed over the LAN, reproduces the app's result.
- A value the app never sent sets a chosen colour on a chosen segment, and leaves the others alone.
- 10 of 10 changes arrived at one per second, over one open connection. The strip echoed each in 125-210 ms.

**Not known. Test these before relying on them:**
- Whether `81` means "one segment follows", so that one command can set several segments. Until then, send one per segment,
  so a full 20-segment repaint takes 20 commands.
- The fastest rate the strip keeps up with. Above one per second is untested.
- Whether the mode byte `00` on DP 61 alone sets the whole strip. In captures, the app's whole-strip colour also changed DP 24.
- Whether a local connection interferes with Home Assistant or the app.
- Whether painted colours survive a power cycle or a later scene or whole-strip colour.
- What DP 21 (mode) must be. It stayed `colour` throughout: if painting does nothing, read DP 21 first.

The strip accepting a command and echoing it doesn't prove the LEDs changed. Anything about the visible result needs a
person looking at the strip, so say so rather than report it as passed.

## Sending

- Keep one connection open and send over it. Tuya devices allow limited local connections, so stop any other local client
  first, including a capture.
- Protocol 3.5 needs a session key negotiation. tinytuya and the f1-dashboard's `tuya-protocol.ts` both handle this,
  though the dashboard's code has only been tested against a fake bulb, not this strip.
- Python: `tinytuya.Device(id, ip, key, version=3.5)`, `set_socketPersistent(True)`, then `set_value(61, value, nowait=True)`
  and `receive()` for the echo.

## Capturing on an unknown strip

1. Connect locally and listen for changes with `receive()`, sending a heartbeat about every 10 seconds. Save the output.
2. Have Chris turn the phone's Wi-Fi off, so the app goes through the cloud and doesn't compete for the local connection.
3. Have them change one thing at a time in the app, in a fixed order: whole strip red, whole strip blue, segment 1 red,
   segment 1 blue, segment 2 red, then every segment a different colour.
4. Compare neighbouring values. Colour-only changes show where the colour is stored; segment-only changes show the addressing.
5. Test in this order, with a person watching: replay a captured value exactly, change only the colour, change only the
   segment, then the rate test.

For a rate test that a person can count afterwards, paint a run of segments white, one per second. Counting white segments is
easier than watching for changes as they happen.

No change on any DP while painting, after checking the phone is on mobile data, means the app may not report it locally.
That doesn't rule out sending, but stop and tell Chris rather than guessing formats.

## Getting a local key

Tuya's developer project is one route (`python -m tinytuya wizard`), but its data centre selection can be blocked by a trial
limit. The other route is the Smart Life QR login that Home Assistant uses: `tuya-device-sharing-sdk`, with the client ID
and schema from Home Assistant's `components/tuya/const.py`. Log in with the app's User Code, and read `local_key` from
`Manager.device_map` after `update_device_cache()`. This needs Chris to scan a QR code, and it returns the keys of every
device on the account, so write only to a file with mode 600 and never print them.
