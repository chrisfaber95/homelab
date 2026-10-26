# LLM-helper (F-017)

Twee jobs op het lokale model van de homelab, voor scripts op je tailnet: **summarize** (tekst samenvatten) en
**extract** (velden of een label uit tekst halen, als JSON). Via het commando `llm` of via een OpenAI-compatibele
API op `http://homelab:8017/v1`. Geen sleutel nodig. Vanaf het LAN buiten Tailscale is de poort dicht.
Er is geen fallback naar een betaalde API: kan de box niet helpen, dan krijg je een fout.

```
Windows / box ──tailnet──> llm-helper :8017 ──> Ollama 127.0.0.1:11434 ──> model op de GPU
                            (usage-log)
```

## `llm` (CLI)

```bash
llm summarize < app.log                      # of: llm summarize app.log andere.log
llm summarize --instructions "in drie bullets" app.log
llm extract --fields naam,bestelnummer,bedrag:number < mail.txt
llm extract --labels bug,feature,vraag < issue.txt          # {"label": "bug"}
llm extract --fields service,poort:integer --labels netwerk,gpu,schijf < fail.log
llm usage --days 7                           # gebruik per caller en per job
```

- **Velden** zijn standaard tekst. Andere types: `:number`, `:integer`, `:boolean`. Staat een veld niet in de tekst,
  dan is het `null`.
- **Labels**: het antwoord krijgt een veld `label` met precies één van de toegestane labels.
- De uitvoer van `extract` is altijd geldige JSON met precies de gevraagde velden. Lukt dat het model niet (de
  helper probeert het twee keer), dan print `llm` niets op stdout en stopt met een fout.

### Caller, timeout en exitcodes

- **Caller** (de naam in het usage-log): `--caller mijn-script` of `LLM_CALLER=mijn-script`. Zonder die naam:
  `unknown`. Het log bewaart daarnaast altijd het Tailscale-apparaat waar de call vandaan kwam.
- **Timeout**: `--timeout 120` (seconden, standaard 60, max. 600). De helper breekt de job daarna af.
- **Adres**: `LLM_URL` of `--url` (standaard `http://homelab:8017`).

| Exit | Betekenis |
| --- | --- |
| 0 | gelukt |
| 1 | de job mislukte (ongeldige uitvoer van het model, tekst te lang, Ollama-fout) |
| 2 | verkeerde aanroep (geen tekst, geen velden, onbekend type) |
| 3 | de homelab is onbereikbaar (binnen 5 s: box uit, Tailscale weg, naam onbekend) |
| 4 | timeout |

Foutmeldingen gaan naar stderr, met `llm:` ervoor.

### Installeren

- **Op de box**: staat er na `scripts/17-llm.sh` (als `/usr/local/bin/llm`).
- **Op Windows**: vereist Python 3 (`py -3 --version`; anders `winget install Python.Python.3.12`). Zet `bin/llm` en
  `bin/llm.cmd` uit deze repo samen in een map die in je `PATH` staat, bijvoorbeeld `%USERPROFILE%\bin`. Daarna werkt
  `llm summarize bestand.txt` in cmd en PowerShell.
  In Windows PowerShell 5 gaat tekst via een pipe (`Get-Content x | llm summarize`) niet als UTF-8. Geef daar een
  bestand mee, of gebruik PowerShell 7.

## API (OpenAI-compatibel)

Basis-URL `http://homelab:8017/v1`. De job is de modelnaam: `summarize` of `extract`. Een API-sleutel is niet nodig
(de SDK wil er een, dus geef iets op). Streaming wordt niet ondersteund.

```python
import json
from openai import OpenAI

client = OpenAI(base_url="http://homelab:8017/v1", api_key="geen", timeout=70)

# summarize
r = client.chat.completions.create(
    model="summarize",
    user="mijn-script",                          # caller in het usage-log
    messages=[{"role": "user", "content": open("fail.log").read()}],
    extra_body={"timeout": 60},                  # job-timeout in seconden
)
print(r.choices[0].message.content)

# extract: een standaard JSON-schema
r = client.chat.completions.create(
    model="extract",
    user="mijn-script",
    messages=[{"role": "user", "content": "Jan (42) woont in Utrecht."}],
    response_format={"type": "json_schema", "json_schema": {"name": "persoon", "schema": {
        "type": "object",
        "properties": {"naam": {"type": "string"}, "leeftijd": {"type": "integer"}, "stad": {"type": "string"}},
        "required": ["naam", "leeftijd", "stad"], "additionalProperties": False}}},
)
print(json.loads(r.choices[0].message.content))

# extract: kort, met velden en labels
r = client.chat.completions.create(
    model="extract",
    user="mijn-script",
    messages=[{"role": "user", "content": open("fail.log").read()}],
    extra_body={"fields": ["service", {"name": "poort", "type": "integer"}], "labels": ["netwerk", "gpu", "schijf"]},
)
print(json.loads(r.choices[0].message.content))
```

Getest met `openai` 3.37 voor Python.

- **Caller**: het OpenAI-veld `user`, of de header `X-LLM-Caller` (gaat voor).
- **Timeout**: het veld `timeout` in de body (`extra_body`). Zet de timeout van de SDK zelf iets hoger, anders breekt
  de SDK eerder af dan de helper.
- **extract**: geef `response_format` met `json_schema` (types, `enum`, `properties`, `items`; het antwoord heeft
  precies de sleutels uit `properties`), of de kortere velden `fields` en `labels` zoals hierboven.
- **Fouten** hebben de OpenAI-vorm `{"error": {"message", "type", "code"}}`:
  400 `bad_request` / `too_long`, 403 `forbidden` (niet van het tailnet), 502 `invalid_output` / `ollama_error`,
  503 `ollama_unreachable`, 504 `timeout`.
- Verder: `GET /v1/models` (de jobs), `GET /usage?days=7` (de totalen van `llm usage`, als JSON), `GET /usage/hourly?days=8` (calls per uur in UTC,
  voor de homelab-pagina),
  `GET /health` (200 als Ollama draait en het model er is, anders 503).

## Grenzen

- **Lengte**: de tekst moet in de context van het model passen (`LLM_NUM_CTX`). Te lange tekst wordt geweigerd
  (`too_long`), niet stilletjes afgekapt: met 4096 is dat vanaf ongeveer 10.000 tekens, met 8192 ongeveer 22.000.
- **Eén GPU**: calls worden na elkaar uitgevoerd. Wachten in de rij telt mee voor de timeout.
- Het model blijft na een call 10 minuten geladen; de eerste call daarna laadt het opnieuw (een paar seconden).

## Usage-log

Elke call, ook een mislukte, staat in `/var/lib/llm-helper/usage.db` (SQLite, tabel `calls`): tijd (UTC), caller,
apparaat, job, model, duur, gelukt of niet, foutmelding, tokens en het deel van het model dat op de GPU zat.
`llm usage --days N` toont de totalen per caller en per job, en per model het laagste GPU-aandeel. Op de desktop hoort
dat 100% te zijn; is het lager, dan past het model met zijn context niet in het VRAM: kies een kleiner model of een
kleinere `LLM_NUM_CTX`. Op de laptop draait het model bewust grotendeels op de CPU (zie hieronder).

## Model wisselen

1. Kies een model uit [ollama.com/library](https://ollama.com/library) dat met zijn context in het VRAM past.
2. Zet `LLM_MODEL` (en eventueel `LLM_NUM_CTX`) in `config/llm.conf`; commit en push.
3. `sudo bash /home/claude/homelab/scripts/17-llm.sh`: haalt het model op en herstart de helper.
4. `ollama ps` na een call: in de kolom PROCESSOR staat hoeveel op de GPU draait (op de desktop: `100% GPU`).
5. Oude modellen opruimen: `ollama list`, `ollama rm <model>`.

### Keuze (2026-10-25, op de laptop)

Vergeleken op een apt-log, een systemd-foutlog en een Nederlandse mail, met 4096 context:

| Model | Op de GPU | Per job | Uitkomst |
| --- | --- | --- | --- |
| `llama3.1:8b` | 16% | 11-40 s | alle velden en labels goed; mail in het Nederlands samengevat |
| `llama3.2:3b` | 34% | 4-23 s | mail goed, foutlog-extract fout |
| `gemma3:4b` | 10% | 9-31 s | vatte een log in het Spaans samen |
| `qwen2.5:3b` | 42% | 5-22 s | verkeerde labels |
| `qwen3:4b` | 51% | timeout | negeert `think: false` en blijft doorpraten |
| `gemma3:1b` | 100% | 2-4 s | foutlog goed, mail in het Engels, labels soms fout |
| `qwen3:1.7b`, `llama3.2:1b`, `qwen2.5:1.5b` | 48-86% | 1-60 s | kopieerden de tekst of gaven lege velden |

Gekozen: `llama3.1:8b`. Op de laptop draait het grotendeels op de CPU: een job bezet dan de vier cores en ~5 GB RAM,
en de langste toegestane tekst duurt ~40 s (binnen de standaard-timeout van 60 s; wachten in de rij telt mee).
Op de desktop past het met 8192 context helemaal in de 8 GB van de GTX 1080 (controleren met `ollama ps`).

## Beheer

- Diensten: `ollama` (alleen `127.0.0.1:11434`) en `llm-helper` (poort 8017; ufw laat hem alleen toe via `tailscale0`,
  en de helper weigert zelf alles buiten loopback en het tailnet).
- Logs: `journalctl -u llm-helper`, `journalctl -u ollama`.
- Ollama wordt niet automatisch bijgewerkt: de versie en de hash staan in `config/llm.conf`.
