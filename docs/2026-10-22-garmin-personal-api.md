# Garmin Connect Personal API — riferimento per HackDiet

> Raccolto il 2026-10-22 dalle pagine ufficiali Garmin e da fonti terze che leggono la specifica OpenAPI.
> La Swagger ufficiale è renderizzata via JavaScript: i nomi esatti dei path REST (tranne quelli indicati sotto) **non sono verificati**. Prima di scrivere codice definitivo, confermare i path con una chiamata reale o con la Swagger aperta da browser.

## 1. Panoramica

| Voce | Dettaglio |
|---|---|
| API | Garmin Connect Personal API — accesso ufficiale ai propri dati Garmin Connect |
| Destinatari | Singoli utenti, solo per i propri dati; non per app commerciali multi-utente |
| Specifica | OpenAPI 1.0.0 su `personal.connectapi.garmin.com` — 26 endpoint, 15 servizi, ~110 schemi |
| Accesso | **Sola lettura**: tutti gli endpoint sono GET |
| Generazione key | Garmin Connect Web → Impostazioni → Personal API Key; accettare i Termini; key mostrata **una sola volta** |
| Scadenza key | 30, 90, 180 giorni o 1 anno; email di promemoria ~1 settimana prima |
| Revoca | Cancellabile in qualsiasi momento; invalida key e tutti i token associati |
| Durata token | 24 ore (o fino alla scadenza della key); **nessun refresh token** |
| Rate limit | Non documentati |
| Costi | Non documentati |

## 2. Autenticazione

```bash
curl -X POST 'https://personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token' \
  -H 'Authorization: ApiKey <API_KEY>'
```

Restituisce un access token OAuth2 da usare come `Authorization: Bearer <token>` sugli altri endpoint.
In una GitHub Action notturna: key nel secret `GARMIN_PERSONAL_API_KEY` → token a ogni run → chiamate → JSON su Dropbox.

## 3. Endpoint (nomi dei metodi della libreria `gcpapi-sync`)

| Area | Metodo | Dati | Limite per richiesta | Uso in HackDiet | Note |
|---|---|---|---|---|---|
| Attività | `search_activities` | Lista attività con filtri | ~45 per pagina | No | Pagine più grandi → HTTP 500 |
| Attività | `iter_activities` | Iteratore attività | paginato | No | |
| Attività | `get_activity` | Dettaglio attività | — | No | |
| Attività | `get_activity_workouts` | Workout dell'attività | — | No | |
| Attività | `download_activity` | FIT/GPX/TCX originale | — | No | Header `Accept: application/x-zip-compressed` |
| Attività | `download_activity_zip` | Zip Garmin | — | No | |
| Riepilogo giornaliero | `get_daily_summary` | Totali del giorno (kcal, FC a riposo…) | 1 giorno | **Sì — kcal, FC a riposo** | Verificare quali campi kcal (attive/totali) corrispondono all'attuale |
| Riepilogo giornaliero | `get_daily_summaries` | Riepiloghi v2 su range | 31 gg | **Sì** | Documentato 1 anno, ma HTTP 500 oltre ~90 gg |
| Sonno | `get_daily_sleep` | Sonno di una notte | 1 giorno | **Sì — sonno** | Timestamp in epoch ms |
| Sonno | `get_daily_sleep_summaries` | Sonno su range | 31 gg | **Sì** | |
| FC | `get_daily_heart_rate` | FC intraday | 1 giorno | Opzionale | |
| FC | `get_heart_rate_zones` | Zone FC | — | No | |
| HRV | `get_daily_hrv` | HRV della notte | 1 giorno | Opzionale | |
| HRV | `get_daily_hrv_summaries` | HRV su range | 365 gg | Opzionale | |
| Stress | `get_daily_stress` | Stress | 28 gg | Opzionale | |
| Passi | `get_daily_steps` | Passi, distanza, obiettivo | 28 gg | No | |
| Idratazione | `get_daily_hydration` | Idratazione | 31 gg | No | |
| Body Battery | `get_daily_body_battery` | Report Body Battery | 31 gg | Opzionale | |
| SpO2 | `get_daily_spo2` | SpO2 | 1 giorno | No | |
| Fitness Age | `get_fitness_age` | Fitness Age | 1 giorno | No | Restituisce oggetto, non array |
| Peso | `get_weight_range` | Pesi (in **grammi**) | ~1 anno | Opzionale (alternativa a Withings) | Usare questo per il peso |
| Peso | `get_weight_entries` | Singole pesate | — | **Non utilizzabile** | `GET /weight-service/weight/all/...` → 400 sempre |
| Peso | `get_effective_weight_goals` | Obiettivi peso | — | No | |
| Nutrizione | `get_daily_food_logs` | Cibi, nutrienti, obiettivi, pasti | 1 giorno | Opzionale | |
| Ciclo | `get_menstrual_cycle_summary` / `_day_view` | Ciclo mestruale | 1 giorno | No | |
| Benessere | `get_wellness_activity_summaries` | Attività benessere | — | No | |
| Profilo | `get_user_profile_settings` | Impostazioni | — | No | |
| — | **Assenti** | Training status, readiness, VO2max, carico | — | — | Fonte: Intervals.icu |

## 4. Differenze tra documentazione e API reale (verificate a ottobre 2026 dall'autore di gcpapi-sync)

- Daily summaries: HTTP 500 per range oltre ~90 giorni (documentato 1 anno).
- Ricerca attività: HTTP 500 oltre ~45 attività per pagina, incluso il page size di default del server.
- `/weight-service/weight/all/...` risponde 400 a ogni input.
- Alcuni timestamp documentati come stringhe arrivano come epoch in millisecondi (sonno, peso).
- Campi dichiarati non-nullable arrivano spesso `null`; ci sono campi non documentati.
- Download attività: richiede `Accept: application/x-zip-compressed`.

## 5. Migrazione di `garmin_sync.yml`

| Aspetto | Oggi (librerie non ufficiali) | Personal API |
|---|---|---|
| Stato | Endpoint privati, possono cambiare senza preavviso; uso non sanzionato dai ToS | Ufficiale, ToS accettati |
| Secrets | Email, password, eventuale MFA, token di sessione | Solo `GARMIN_PERSONAL_API_KEY` |
| Login | Fragile (SSO, MFA, anti-bot) | Una POST per il token a ogni run |
| Manutenzione | Si rompe ai cambi di SSO | Rotazione key al massimo 1 volta l'anno |

**Opzione A — `gcpapi-sync`** (`pip install gcpapi-sync`, Python ≥ 3.11): gestisce token, retry e split dei range; legge la key da `GARMIN_PERSONAL_API_KEY`.

```python
from gcpapi_sync import GarminClient

with GarminClient() as garmin:
    summary = garmin.get_daily_summary("2026-10-21")
    sleep = garmin.get_daily_sleep("2026-10-21")
    weights = garmin.get_weight_range("2026-10-01", "2026-10-21")  # grammi
```

**Opzione B — chiamate dirette con `requests`**: poche chiamate; servono i path esatti (non verificati, vedi nota in testa).

**Vincoli da rispettare:** il formato dei JSON scritti su Dropbox deve restare identico a quello attuale, perché lo leggono il sito Flask (kcal) e il task della dashboard.

**Primo test consigliato:** daily summary di un giorno noto, confrontando i campi kcal con il JSON attuale.

## 6. Contesto

Secondo the5krunner, Garmin ha ristretto i termini per i licenziatari: non potranno passare dati Garmin a servizi non approvati. Data di efficacia non annunciata. Se Intervals.icu riceve i dati da Garmin, potrebbe esserne toccato; la Personal API resta una via diretta indipendente.

## Fonti

- https://developer.garmin.com/gc-developer-program/personal/personal-api/
- https://developer.garmin.com/gc-developer-program/personal/personal-faq/
- https://developer.garmin.com/gc-developer-program/personal/swagger-docs/
- https://www.badgehero.io/post/2026-10-21-garmin-personal-api-badges
- https://pypi.org/project/gcpapi-sync/
