from __future__ import annotations

import base64
import hashlib
import json
import os
from datetime import datetime, timezone
from typing import Any

from route_geometry import DEFAULT_MISSION_RADIUS_METER, snap_sensor_to_access_point


UNUSED_OFFICIAL_SENSOR_NAMES = {"pws01", "vs01", "vs02"}
NON_MOBILE_SENSOR_IDS = {"SENSOR_007", "SENSOR_015", "SENSOR_031", "SENSOR_032", "SENSOR_033"}

COLLECTION_NAMES = [
    "users",
    "sensors",
    "sensor_readings",
    "daily_sensor_stats",
    "mission_results",
    "route_recommendations",
    "reward_exchanges",
]


def load_local_env() -> None:
    disable_broken_local_proxy()
    env_path = os.path.join(os.path.dirname(__file__), ".env")
    if not os.path.exists(env_path):
        return

    with open(env_path, "r", encoding="utf-8") as env_file:
        for raw_line in env_file:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            os.environ.setdefault(key, value)


def disable_broken_local_proxy() -> None:
    for key in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"]:
        value = os.environ.get(key, "")
        if "127.0.0.1:9" in value or "localhost:9" in value:
            os.environ.pop(key, None)
    os.environ["NO_PROXY"] = "*"
    os.environ["no_proxy"] = "*"


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def today_key() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def hash_password(raw_password: str) -> str:
    return hashlib.sha256(raw_password.encode("utf-8")).hexdigest()


def next_id(prefix: str) -> str:
    stamp = datetime.now().strftime("%Y%m%d%H%M%S%f")
    return f"{prefix}_{stamp}"


def sensor_id_from_name(sensor_name: str | None) -> str:
    if not sensor_name:
        return next_id("SENSOR")
    digits = "".join(ch for ch in sensor_name if ch.isdigit())
    if digits:
        return f"SENSOR_{int(digits):03d}"
    return sensor_name.upper().replace(" ", "_")


def official_reading_id(sensor_id: str, measured_at: str, temperature: Any, co2: Any) -> str:
    fingerprint = f"{sensor_id}|{measured_at}|{temperature}|{co2}"
    digest = hashlib.sha1(fingerprint.encode("utf-8")).hexdigest()[:16]
    return f"OFFICIAL_{sensor_id}_{digest}"


def normalized_sensor_name(sensor_name: str) -> str:
    return sensor_name.lower().replace(" ", "").replace("_", "").replace("-", "")


def is_unused_official_sensor_name(sensor_name: str) -> bool:
    return normalized_sensor_name(sensor_name) in UNUSED_OFFICIAL_SENSOR_NAMES


def daily_stat_doc_id(stat_date: str, sensor_id: str) -> str:
    return f"{stat_date}_{sensor_id}"


def default_sensor_rows(now: str | None = None) -> list[dict[str, Any]]:
    counts = [
        22, 4, 7, 18, 12, 9, 2, 25, 13, 3, 6, 14, 11, 5, 1, 8, 19,
        16, 2, 21, 15, 10, 17, 6, 23, 12, 3, 20, 9, 7, 1, 4, 5, 18,
    ]
    now = now or utc_now_iso()
    rows = []
    for index in range(1, 35):
        row = (index - 1) // 6
        col = (index - 1) % 6
        latitude = 36.626906 + row * 0.00058 + col * 0.00013
        longitude = 127.457722 + col * 0.00045 - row * 0.00008
        access_point = snap_sensor_to_access_point(latitude, longitude)
        rows.append(
            {
                "sensor_id": f"SENSOR_{index:03d}",
                "sensor_name": f"sensor {index:02d}",
                "mac_address": None,
                "latitude": latitude,
                "longitude": longitude,
                "route_latitude": access_point["latitude"],
                "route_longitude": access_point["longitude"],
                "mission_radius_meter": DEFAULT_MISSION_RADIUS_METER,
                "route_snap_distance_meter": access_point["snapDistanceMeter"],
                "active": True,
                "latest_temperature": 22.0 + (index % 7) * 0.7,
                "latest_co2": 420 + (index % 9) * 28,
                "latest_measured_at": now,
                "latest_fresh": index % 5 != 0,
                "updated_at": now,
                "collected_count_today": counts[index - 1],
            }
        )
    return rows


class FirestoreDatabase:
    def __init__(
        self,
        collection_prefix: str | None = None,
        app_name: str = "campus-collector",
    ) -> None:
        load_local_env()
        self.collection_prefix = collection_prefix
        if self.collection_prefix is None:
            self.collection_prefix = os.environ.get(
                "FIRESTORE_COLLECTION_PREFIX",
                os.environ.get("FIREBASE_COLLECTION_PREFIX", ""),
            )
        self.client, self._firestore = self._create_firestore_client(app_name)

    def _create_firestore_client(self, app_name: str):
        try:
            import firebase_admin
            from firebase_admin import credentials, firestore
        except ImportError as exc:
            raise RuntimeError(
                "firebase-admin is not installed. Run `pip install -r requirements.txt`."
            ) from exc

        try:
            firebase_app = firebase_admin.get_app(app_name)
        except ValueError:
            service_account_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT")
            service_account_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON")
            service_account_base64 = os.environ.get("FIREBASE_SERVICE_ACCOUNT_BASE64")
            options: dict[str, Any] = {}
            project_id = os.environ.get("FIREBASE_PROJECT_ID")
            if project_id:
                options["projectId"] = project_id

            credential = None
            if service_account_path:
                credential = credentials.Certificate(service_account_path)
            elif service_account_json:
                credential = credentials.Certificate(json.loads(service_account_json))
            elif service_account_base64:
                decoded_json = base64.b64decode(service_account_base64).decode("utf-8")
                credential = credentials.Certificate(json.loads(decoded_json))
            firebase_app = firebase_admin.initialize_app(
                credential=credential,
                options=options or None,
                name=app_name,
            )

        return firestore.client(firebase_app), firestore

    def collection(self, name: str):
        return self.client.collection(f"{self.collection_prefix}{name}")

    def initialize(self) -> None:
        now = utc_now_iso()
        user_ref = self.collection("users").document("USER_001")
        if not user_ref.get().exists:
            user_ref.set(
                {
                    "user_id": "USER_001",
                    "login_id": "soohyun",
                    "password_hash": hash_password("1234"),
                    "nickname": "soohyun",
                    "total_point": 1250,
                    "created_at": now,
                }
            )

        stat_date = today_key()
        for sensor in default_sensor_rows(now):
            sensor_id = sensor["sensor_id"]
            sensor_payload = {
                key: value
                for key, value in sensor.items()
                if key != "collected_count_today"
            }
            sensor_ref = self.collection("sensors").document(sensor_id)
            if not sensor_ref.get().exists:
                sensor_ref.set(sensor_payload)

            stat_ref = self.collection("daily_sensor_stats").document(
                daily_stat_doc_id(stat_date, sensor_id)
            )
            if not stat_ref.get().exists:
                stat_ref.set(
                    {
                        "stat_date": stat_date,
                        "sensor_id": sensor_id,
                        "collected_count": sensor["collected_count_today"],
                        "latest_reading_id": None,
                        "updated_at": now,
                    }
                )

    def upsert_sensors_from_official_api(self, sensors: list[dict[str, Any]]) -> int:
        return self.sync_official_sensors(sensors)["syncedSensorCount"]

    def sync_official_sensors(self, sensors: list[dict[str, Any]]) -> dict[str, Any]:
        synced_sensor_count = 0
        stored_reading_count = 0
        now = utc_now_iso()
        stat_date = today_key()

        for item in sensors:
            sensor_name = item.get("sensor") or item.get("sensorName")
            if not sensor_name or is_unused_official_sensor_name(sensor_name):
                continue

            sensor_id = sensor_id_from_name(sensor_name)
            measured_at = item.get("time") or item.get("created_at") or now
            temperature = float(item.get("temperature") or item.get("temp") or 0.0)
            co2 = int(item.get("co2") or item.get("eco2") or 0)
            latitude = float(item.get("latitude") or item.get("lat") or 0.0)
            longitude = float(item.get("longitude") or item.get("lon") or 0.0)
            sensor_ref = self.collection("sensors").document(sensor_id)
            sensor_snapshot = sensor_ref.get()
            route_payload = self.route_payload_if_missing(
                sensor_snapshot.to_dict() or {},
                latitude,
                longitude,
            )

            sensor_ref.set(
                {
                    "sensor_id": sensor_id,
                    "sensor_name": sensor_name,
                    "latitude": latitude,
                    "longitude": longitude,
                    "active": True,
                    "latest_temperature": temperature,
                    "latest_co2": co2,
                    "latest_measured_at": measured_at,
                    "latest_fresh": bool(item.get("fresh")),
                    "updated_at": now,
                    **route_payload,
                },
                merge=True,
            )

            reading_id = official_reading_id(
                sensor_id=sensor_id,
                measured_at=measured_at,
                temperature=temperature,
                co2=co2,
            )
            reading_ref = self.collection("sensor_readings").document(reading_id)
            if not reading_ref.get().exists:
                reading_ref.set(
                    {
                        "reading_id": reading_id,
                        "user_id": None,
                        "sensor_id": sensor_id,
                        "sensor_name": sensor_name,
                        "mac_address": None,
                        "temperature": temperature,
                        "co2": co2,
                        "latitude": latitude,
                        "longitude": longitude,
                        "rssi": None,
                        "collected_at": measured_at,
                        "saved_at": now,
                        "source": "official_api",
                    }
                )
                stored_reading_count += 1

            stat_ref = self.collection("daily_sensor_stats").document(
                daily_stat_doc_id(stat_date, sensor_id)
            )
            if not stat_ref.get().exists:
                stat_ref.set(
                    {
                        "stat_date": stat_date,
                        "sensor_id": sensor_id,
                        "collected_count": 0,
                        "latest_reading_id": None,
                        "updated_at": now,
                    }
                )

            synced_sensor_count += 1

        fresh_sensor_count = sum(
            1 for sensor in self.list_sensors_with_counts() if sensor["fresh"]
        )
        return {
            "syncedSensorCount": synced_sensor_count,
            "storedReadingCount": stored_reading_count,
            "freshSensorCount": fresh_sensor_count,
            "syncedAt": now,
        }

    def list_sensors_with_counts(self, stat_date: str | None = None) -> list[dict[str, Any]]:
        stat_date = stat_date or today_key()
        stats_by_sensor_id = {
            snapshot.to_dict()["sensor_id"]: snapshot.to_dict()
            for snapshot in self.collection("daily_sensor_stats")
            .where("stat_date", "==", stat_date)
            .stream()
            if snapshot.to_dict()
        }

        sensors = []
        for snapshot in self.collection("sensors").stream():
            item = snapshot.to_dict() or {}
            if not item.get("active", True):
                continue
            sensor_id = item.get("sensor_id") or snapshot.id
            stat = stats_by_sensor_id.get(sensor_id, {})
            latitude = float(item.get("latitude") or 0.0)
            longitude = float(item.get("longitude") or 0.0)
            route_payload = self.route_payload(item, latitude, longitude)
            sensors.append(
                {
                    "sensor_id": sensor_id,
                    "sensor_name": item.get("sensor_name", sensor_id),
                    "mac_address": item.get("mac_address"),
                    "latitude": latitude,
                    "longitude": longitude,
                    "route_latitude": route_payload["route_latitude"],
                    "route_longitude": route_payload["route_longitude"],
                    "mission_radius_meter": route_payload["mission_radius_meter"],
                    "route_snap_distance_meter": route_payload["route_snap_distance_meter"],
                    "active": 1 if item.get("active", True) else 0,
                    "temperature": item.get("latest_temperature"),
                    "co2": item.get("latest_co2"),
                    "measured_at": item.get("latest_measured_at"),
                    "fresh": bool(item.get("latest_fresh")),
                    "collected_count_today": int(stat.get("collected_count") or 0),
                }
            )
        return sorted(sensors, key=lambda sensor: sensor["sensor_name"])

    def list_collectible_sensors_with_counts(self, stat_date: str | None = None) -> list[dict[str, Any]]:
        return [
            sensor
            for sensor in self.list_sensors_with_counts(stat_date)
            if sensor["sensor_id"] not in NON_MOBILE_SENSOR_IDS
            and not is_unused_official_sensor_name(sensor["sensor_name"])
        ]

    def table_counts(self) -> dict[str, int]:
        return {
            collection_name: sum(1 for _ in self.collection(collection_name).stream())
            for collection_name in COLLECTION_NAMES
        }

    def daily_sensor_count(self, sensor_id: str, stat_date: str | None = None) -> int:
        stat_date = stat_date or today_key()
        snapshot = self.collection("daily_sensor_stats").document(
            daily_stat_doc_id(stat_date, sensor_id)
        ).get()
        if not snapshot.exists:
            return 0
        return int((snapshot.to_dict() or {}).get("collected_count") or 0)

    def insert_sensor_reading(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = utc_now_iso()
        user_id = payload.get("userId")
        sensor_name = payload.get("sensorName") or payload.get("sensor") or payload.get("sensorId")
        sensor_id = payload.get("sensorId") or sensor_id_from_name(sensor_name)
        sensor_name = sensor_name or sensor_id
        reading_id = next_id("READING")
        collected_at = payload.get("collectedAt") or now
        temperature = float(payload["temperature"])
        co2 = int(payload["co2"])
        latitude = float(payload["latitude"])
        longitude = float(payload["longitude"])
        mac_address = payload.get("macAddress")
        rssi = payload.get("rssi")
        sensor_ref = self.collection("sensors").document(sensor_id)
        sensor_snapshot = sensor_ref.get()
        route_payload = self.route_payload_if_missing(
            sensor_snapshot.to_dict() or {},
            latitude,
            longitude,
        )

        sensor_ref.set(
            {
                "sensor_id": sensor_id,
                "sensor_name": sensor_name,
                "mac_address": mac_address,
                "latitude": latitude,
                "longitude": longitude,
                "active": True,
                "latest_temperature": temperature,
                "latest_co2": co2,
                "latest_measured_at": collected_at,
                "latest_fresh": True,
                "updated_at": now,
                **route_payload,
            },
            merge=True,
        )
        self.collection("sensor_readings").document(reading_id).set(
            {
                "reading_id": reading_id,
                "user_id": user_id,
                "sensor_id": sensor_id,
                "sensor_name": sensor_name,
                "mac_address": mac_address,
                "temperature": temperature,
                "co2": co2,
                "latitude": latitude,
                "longitude": longitude,
                "rssi": int(rssi) if rssi is not None else None,
                "collected_at": collected_at,
                "saved_at": now,
                "source": payload.get("source", "mobile"),
            }
        )

        stat_ref = self.collection("daily_sensor_stats").document(
            daily_stat_doc_id(today_key(), sensor_id)
        )
        if stat_ref.get().exists:
            stat_ref.update(
                {
                    "collected_count": self._firestore.Increment(1),
                    "latest_reading_id": reading_id,
                    "updated_at": now,
                }
            )
        else:
            stat_ref.set(
                {
                    "stat_date": today_key(),
                    "sensor_id": sensor_id,
                    "collected_count": 1,
                    "latest_reading_id": reading_id,
                    "updated_at": now,
                }
            )

        return {"readingId": reading_id, "sensorId": sensor_id, "savedAt": now}

    def save_route_recommendation(
        self,
        user_id: str | None,
        current_latitude: float,
        current_longitude: float,
        max_distance_meter: int,
        route_option_count: int,
        payload: dict[str, Any],
    ) -> str:
        route_id = next_id("ROUTE_REQUEST")
        self.collection("route_recommendations").document(route_id).set(
            {
                "route_id": route_id,
                "user_id": user_id,
                "requested_at": utc_now_iso(),
                "current_latitude": current_latitude,
                "current_longitude": current_longitude,
                "max_distance_meter": max_distance_meter,
                "route_option_count": route_option_count,
                "payload": payload,
            }
        )
        return route_id

    def get_user_by_login(self, login_id: str, raw_password: str) -> dict[str, Any] | None:
        password_hash = hash_password(raw_password)
        users = (
            self.collection("users")
            .where("login_id", "==", login_id)
            .limit(1)
            .stream()
        )
        for snapshot in users:
            user = snapshot.to_dict() or {}
            if user.get("password_hash") == password_hash:
                return {
                    "user_id": user.get("user_id", snapshot.id),
                    "nickname": user.get("nickname", ""),
                    "total_point": int(user.get("total_point") or 0),
                }
        return None

    def record_mission_result(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = utc_now_iso()
        mission_result_id = next_id("MISSION_RESULT")
        user_id = payload["userId"]
        score = int(payload.get("score", 1))
        earned_point = score * 10
        started_at = payload.get("startedAt") or now
        ended_at = payload.get("endedAt") or now

        self.collection("mission_results").document(mission_result_id).set(
            {
                "mission_result_id": mission_result_id,
                "user_id": user_id,
                "started_sensor_id": payload["startedSensorId"],
                "mission_type": payload.get("missionType", "ONE_MINUTE_GAME"),
                "score": score,
                "earned_point": earned_point,
                "started_at": started_at,
                "ended_at": ended_at,
                "saved_at": now,
            }
        )

        user_ref = self.collection("users").document(user_id)
        if user_ref.get().exists:
            user_ref.update({"total_point": self._firestore.Increment(earned_point)})
        else:
            user_ref.set(
                {
                    "user_id": user_id,
                    "login_id": user_id.lower(),
                    "password_hash": "",
                    "nickname": user_id,
                    "total_point": earned_point,
                    "created_at": now,
                }
            )
        user = user_ref.get().to_dict() or {}
        daily_count = sum(
            1
            for snapshot in self.collection("mission_results")
            .where("user_id", "==", user_id)
            .stream()
            if (snapshot.to_dict() or {}).get("saved_at", "").startswith(today_key())
        )

        return {
            "missionResultId": mission_result_id,
            "earnedPoint": earned_point,
            "totalPoint": int(user.get("total_point") or 0),
            "dailyMissionCount": daily_count,
        }

    def reward_summary(self, user_id: str = "USER_001") -> dict[str, Any]:
        users = {
            snapshot.id: (snapshot.to_dict() or {})
            for snapshot in self.collection("users").stream()
        }
        aggregates = {
            snapshot_id: {"mission_count": 0, "point": 0}
            for snapshot_id in users
        }
        for snapshot in self.collection("mission_results").stream():
            mission = snapshot.to_dict() or {}
            mission_user_id = mission.get("user_id")
            if not mission_user_id:
                continue
            aggregates.setdefault(mission_user_id, {"mission_count": 0, "point": 0})
            aggregates[mission_user_id]["mission_count"] += 1
            aggregates[mission_user_id]["point"] += int(mission.get("earned_point") or 0)

        ranking_rows = sorted(
            [
                {
                    "user_id": item_user_id,
                    "nickname": users.get(item_user_id, {}).get("nickname", item_user_id),
                    "mission_count": aggregate["mission_count"],
                    "point": aggregate["point"],
                }
                for item_user_id, aggregate in aggregates.items()
            ],
            key=lambda row: (-row["mission_count"], -row["point"], row["nickname"]),
        )[:20]
        rankings = [
            {
                "rank": index + 1,
                "userId": row["user_id"],
                "nickname": row["nickname"],
                "missionCount": row["mission_count"],
                "point": row["point"],
            }
            for index, row in enumerate(ranking_rows)
        ]

        user = users.get(user_id, {})
        total_point = int(user.get("total_point") or 0)
        my_rank = next((row["rank"] for row in rankings if row["userId"] == user_id), None)
        return {
            "user": {
                "userId": user_id,
                "nickname": user.get("nickname", ""),
                "totalPoint": total_point,
                "exchangeRate": {"point": 1000, "reward": 1},
                "exchangeableReward": total_point // 1000,
                "remainingPoint": total_point % 1000,
            },
            "myRanking": {
                "rankingType": "daily",
                "rank": my_rank,
            },
            "rankings": rankings,
        }

    def exchange_reward(self, user_id: str, point_cost: int, reward_won: int) -> dict[str, Any]:
        if point_cost <= 0 or reward_won <= 0:
            raise ValueError("교환 요청 금액이 올바르지 않습니다.")

        user_ref = self.collection("users").document(user_id)
        user_snapshot = user_ref.get()
        if not user_snapshot.exists:
            raise ValueError("사용자 정보를 찾을 수 없습니다.")

        user = user_snapshot.to_dict() or {}
        total_point = int(user.get("total_point") or 0)
        if total_point < point_cost:
            raise ValueError("보유 포인트가 부족합니다.")

        remaining_point = total_point - point_cost
        now = utc_now_iso()
        exchange_id = next_id("REWARD_EXCHANGE")
        user_ref.update({"total_point": remaining_point})
        self.collection("reward_exchanges").document(exchange_id).set(
            {
                "exchange_id": exchange_id,
                "user_id": user_id,
                "point_cost": point_cost,
                "reward_won": reward_won,
                "exchanged_at": now,
            }
        )
        return {
            "exchangeId": exchange_id,
            "userId": user_id,
            "usedPoint": point_cost,
            "rewardWon": reward_won,
            "totalPoint": remaining_point,
            "exchangedAt": now,
        }

    def route_payload_if_missing(
        self,
        existing: dict[str, Any],
        latitude: float,
        longitude: float,
    ) -> dict[str, Any]:
        return self.route_payload(existing, latitude, longitude)

    def route_payload(self, item: dict[str, Any], latitude: float, longitude: float) -> dict[str, Any]:
        access_point = snap_sensor_to_access_point(latitude, longitude)
        return {
            "route_latitude": float(access_point["latitude"]),
            "route_longitude": float(access_point["longitude"]),
            "mission_radius_meter": int(
                item.get("mission_radius_meter", item.get("missionRadiusMeter", DEFAULT_MISSION_RADIUS_METER))
                or DEFAULT_MISSION_RADIUS_METER
            ),
            "route_snap_distance_meter": int(access_point["snapDistanceMeter"]),
            "route_source": access_point["source"],
        }


Database = FirestoreDatabase
