"""
GitHub Actions sync: Garmin Personal API → Dropbox.
Richiede: GARMIN_PERSONAL_API_KEY, DROPBOX_REFRESH_TOKEN, DROPBOX_APP_KEY, DROPBOX_APP_SECRET
"""

import json
import os
import requests
from datetime import date, timedelta

from gcpapi_sync import GarminClient

DROPBOX_KCAL_PATH = "/hackdiet_kcal.json"
DROPBOX_SLEEP_PATH = "/hackdiet_sleep.json"
DAYS_BACK = int(os.environ.get("DAYS_BACK", 7))


def _attr(obj, *names, default=None):
    """Legge un attributo o chiave da un oggetto gcpapi-sync (che può essere dataclass o dict)."""
    for name in names:
        try:
            v = getattr(obj, name, None)
            if v is None and isinstance(obj, dict):
                v = obj.get(name)
            if v is not None:
                return v
        except Exception:
            pass
    return default


def sync_kcal(garmin):
    end = date.today()
    start = end - timedelta(days=DAYS_BACK)
    kcal_map = {}
    cur = start
    while cur <= end:
        ds = cur.isoformat()
        try:
            data = garmin.get_daily_summary(ds)
            if data:
                # calorie CONSUMATE (diario alimentare), non quelle bruciate (active + bmr)
                consumed = _attr(data, "consumed_kilocalories", default=0) or 0
                if int(consumed) > 0:
                    kcal_map[ds] = int(consumed)
                    print(f"  {ds}: {int(consumed)} kcal consumate")
                else:
                    print(f"  {ds}: kcal consumate zero o assenti")
        except Exception as e:
            print(f"  {ds}: errore kcal - {e}")
        cur += timedelta(days=1)
    return kcal_map


def sync_sleep(garmin):
    end = date.today()
    start = end - timedelta(days=DAYS_BACK)
    sleep_map = {}
    cur = start
    while cur <= end:
        ds = cur.isoformat()
        try:
            summary = garmin.get_daily_summary(ds)
            rhr = _attr(summary, "resting_heart_rate", "restingHeartRate") if summary else None

            sleep = garmin.get_daily_sleep(ds)
            entry = {}
            if sleep:
                # attributi snake_case della dataclass DailySleepDTO
                deep  = _attr(sleep, "deep_sleep_seconds",  default=0) or 0
                rem   = _attr(sleep, "rem_sleep_seconds",   default=0) or 0
                light = _attr(sleep, "light_sleep_seconds", default=0) or 0
                total = _attr(sleep, "sleep_time_seconds", "total_sleep_seconds", default=0) or 0
                awake = _attr(sleep, "awake_sleep_seconds", default=0) or 0
                if total:
                    entry["total"] = round(total / 60)
                if deep:
                    entry["deep"]  = round(deep  / 60)
                if rem:
                    entry["rem"]   = round(rem   / 60)
                if light:
                    entry["light"] = round(light / 60)
                if awake:
                    entry["awake"] = round(awake / 60)

            if rhr:
                entry["rhr"] = int(rhr)

            if entry:
                sleep_map[ds] = entry
                print(f"  {ds}: {entry}")
            else:
                print(f"  {ds}: nessun dato sonno/rhr")
        except Exception as e:
            print(f"  {ds}: errore sonno/rhr - {e}")
        cur += timedelta(days=1)
    return sleep_map


def get_dropbox_access_token():
    r = requests.post(
        "https://api.dropboxapi.com/oauth2/token",
        data={
            "grant_type": "refresh_token",
            "refresh_token": os.environ["DROPBOX_REFRESH_TOKEN"],
            "client_id": os.environ["DROPBOX_APP_KEY"],
            "client_secret": os.environ["DROPBOX_APP_SECRET"],
        },
        timeout=15,
    )
    r.raise_for_status()
    return r.json()["access_token"]


def download_from_dropbox(token, path):
    headers = {
        "Authorization": f"Bearer {token}",
        "Dropbox-API-Arg": json.dumps({"path": path}),
        "Content-Type": "",
    }
    r = requests.post(
        "https://content.dropboxapi.com/2/files/download",
        headers=headers, data=b"", timeout=30,
    )
    if r.status_code == 200:
        return r.json()
    return {}


def upload_to_dropbox(token, path, data_map):
    data = json.dumps(data_map).encode()
    headers = {
        "Authorization": f"Bearer {token}",
        "Dropbox-API-Arg": json.dumps({"path": path, "mode": "overwrite", "autorename": False}),
        "Content-Type": "application/octet-stream",
    }
    r = requests.post(
        "https://content.dropboxapi.com/2/files/upload",
        headers=headers, data=data, timeout=30,
    )
    r.raise_for_status()
    print(f"Caricato su Dropbox: {path} ({len(data_map)} giorni)")


def main():
    print(f"=== Garmin Personal API → Dropbox CI Sync (ultimi {DAYS_BACK} giorni) ===")

    with GarminClient() as garmin:
        print("Autenticazione OK.")

        print("Sync kcal...")
        kcal_map = sync_kcal(garmin)
        print(f"Trovati {len(kcal_map)} giorni con kcal")

        print("Sync sonno + RHR...")
        sleep_map = sync_sleep(garmin)
        print(f"Trovati {len(sleep_map)} giorni con dati sonno/RHR")

    print("Ottengo access token Dropbox...")
    dropbox_token = get_dropbox_access_token()

    existing_kcal = download_from_dropbox(dropbox_token, DROPBOX_KCAL_PATH)
    existing_sleep = download_from_dropbox(dropbox_token, DROPBOX_SLEEP_PATH)
    merged_kcal = {**existing_kcal, **kcal_map}

    # correzioni manuali opzionali, es. KCAL_OVERRIDES='{"2026-10-17": 2820}'
    overrides = os.environ.get("KCAL_OVERRIDES", "").strip()
    if overrides:
        for day, value in json.loads(overrides).items():
            merged_kcal[day] = int(value)
            print(f"  Correzione manuale: {day} = {int(value)} kcal")
    merged_sleep = {**existing_sleep, **sleep_map}

    upload_to_dropbox(dropbox_token, DROPBOX_KCAL_PATH, merged_kcal)
    upload_to_dropbox(dropbox_token, DROPBOX_SLEEP_PATH, merged_sleep)
    print("Fatto!")


if __name__ == "__main__":
    main()
