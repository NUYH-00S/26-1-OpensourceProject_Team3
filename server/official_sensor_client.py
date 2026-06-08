from __future__ import annotations

import json
import os
import urllib.request
from typing import Any


DEFAULT_OFFICIAL_SENSOR_API_URL = "http://203.255.81.72:10021/sensor/api/map"
OFFICIAL_SENSOR_API_URL = os.environ.get(
    "OFFICIAL_SENSOR_API_URL",
    DEFAULT_OFFICIAL_SENSOR_API_URL,
)
UNUSED_SENSOR_NAMES = {"pws01", "vs01", "vs02"}


def fetch_official_sensors(
    endpoint: str = OFFICIAL_SENSOR_API_URL,
    timeout_second: int = 5,
) -> list[dict[str, Any]]:
    with urllib.request.urlopen(endpoint, timeout=timeout_second) as response:
        raw = response.read().decode("utf-8")
    return normalize_official_sensors(json.loads(raw))


def normalize_official_sensors(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    sensors = []
    for item in items:
        sensor_name = item.get("sensor") or item.get("sensorName")
        if not sensor_name or is_unused_sensor_name(sensor_name):
            continue
        sensors.append(
            {
                "sensor": sensor_name,
                "latitude": float(item.get("latitude") or item.get("lat") or 0.0),
                "longitude": float(item.get("longitude") or item.get("lon") or 0.0),
                "temperature": float(item.get("temperature") or item.get("temp") or 0.0),
                "co2": int(item.get("co2") or item.get("eco2") or 0),
                "time": item.get("time") or item.get("created_at"),
                "fresh": bool(item.get("fresh")),
            }
        )
    return sensors


def normalized_sensor_name(sensor_name: str) -> str:
    return sensor_name.lower().replace(" ", "").replace("_", "").replace("-", "")


def is_unused_sensor_name(sensor_name: str) -> bool:
    return normalized_sensor_name(sensor_name) in UNUSED_SENSOR_NAMES
