from __future__ import annotations

import argparse

from firebase_store import Database
from official_sensor_client import OFFICIAL_SENSOR_API_URL, fetch_official_sensors


def main() -> None:
    parser = argparse.ArgumentParser(description="Sync official TA sensor API data into Firebase Firestore.")
    parser.add_argument("--endpoint", default=OFFICIAL_SENSOR_API_URL, help="Official sensor API endpoint.")
    args = parser.parse_args()

    db = Database()
    db.initialize()
    sensors = fetch_official_sensors(args.endpoint)
    result = db.sync_official_sensors(sensors)

    print("DB: Firebase Firestore")
    print(f"Fetched from TA server: {len(sensors)}")
    print(f"Synced sensors: {result['syncedSensorCount']}")
    print(f"Stored official readings: {result['storedReadingCount']}")
    print(f"Fresh sensors: {result['freshSensorCount']}")
    print(f"Synced at: {result['syncedAt']}")


if __name__ == "__main__":
    main()
