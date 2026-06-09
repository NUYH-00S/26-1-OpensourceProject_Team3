from __future__ import annotations

import copy
import json
import os
from statistics import mean
from time import monotonic
from typing import Any

from flask import Flask, Response, request

from firebase_store import (
    Database,
    NON_MOBILE_SENSOR_IDS,
    is_unused_official_sensor_name,
    sensor_id_from_name,
)
from official_sensor_client import fetch_official_sensors
from route_engine import recommend_routes


app = Flask(__name__)
app.json.sort_keys = False
db = Database()
db.initialize()
READ_CACHE_TTL_SECONDS = int(os.environ.get("FIREBASE_READ_CACHE_TTL_SECONDS", "300"))
read_cache: dict[str, tuple[float, list[dict[str, Any]]]] = {}
route_cache: dict[tuple[float, float, int, int], tuple[float, list[dict[str, Any]]]] = {}


def official_sync_enabled() -> bool:
    return os.environ.get("OFFICIAL_SYNC_ENABLED", "true").lower() not in {
        "0",
        "false",
        "no",
        "off",
    }


def clear_read_cache() -> None:
    read_cache.clear()
    route_cache.clear()


def cached_sensors(*, collectible: bool = False) -> list[dict[str, Any]]:
    key = "collectible_sensors" if collectible else "all_sensors"
    now = monotonic()
    cached = read_cache.get(key)
    if cached and cached[0] > now:
        return [dict(sensor) for sensor in cached[1]]

    all_cached = read_cache.get("all_sensors")
    if collectible and all_cached and all_cached[0] > now:
        sensors = [
            sensor
            for sensor in all_cached[1]
            if sensor["sensor_id"] not in NON_MOBILE_SENSOR_IDS
            and not is_unused_official_sensor_name(sensor["sensor_name"])
        ]
        read_cache[key] = (all_cached[0], [dict(sensor) for sensor in sensors])
        return [dict(sensor) for sensor in sensors]

    sensors = (
        db.list_collectible_sensors_with_counts()
        if collectible
        else db.list_sensors_with_counts()
    )
    read_cache[key] = (now + READ_CACHE_TTL_SECONDS, [dict(sensor) for sensor in sensors])
    return [dict(sensor) for sensor in sensors]


def cached_routes(
    *,
    sensors: list[dict[str, Any]],
    current_latitude: float,
    current_longitude: float,
    max_distance_meter: int,
    route_option_count: int,
) -> list[dict[str, Any]]:
    now = monotonic()
    key = (
        round(current_latitude, 4),
        round(current_longitude, 4),
        max_distance_meter,
        route_option_count,
    )
    cached = route_cache.get(key)
    if cached and cached[0] > now:
        return copy.deepcopy(cached[1])

    routes = recommend_routes(
        sensors=sensors,
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        max_distance_meter=max_distance_meter,
        route_option_count=route_option_count,
    )
    route_cache[key] = (now + READ_CACHE_TTL_SECONDS, copy.deepcopy(routes))
    return copy.deepcopy(routes)


@app.get("/")
def home():
    return "Walking Ritual Flask Server is running!"


@app.get("/healthz")
def healthz():
    return success({"status": "ok"}, "서버가 정상 동작 중입니다.")


@app.post("/api/v1/sensors/sync")
def sync_sensors():
    result = sync_from_ta_server()
    return success(
        result,
        "자동 센서 데이터 동기화가 완료되었습니다.",
    )


@app.get("/api/v1/sensors")
def list_sensors():
    sync = request.args.get("sync", "false").lower() == "true"
    sync_result = sync_from_ta_server() if sync else None
    return success(
        {
            "sync": sync_result,
            "sensors": [sensor_response(sensor) for sensor in cached_sensors()],
        },
        "센서 목록 조회에 성공했습니다.",
    )


@app.get("/api/v1/app/bootstrap")
def app_bootstrap():
    sync = request.args.get("sync", "true").lower() != "false"
    user_id = request.args.get("userId", "USER_001")
    current_latitude = float(request.args.get("currentLatitude", 36.628123))
    current_longitude = float(request.args.get("currentLongitude", 127.457891))
    max_distance_meter = int(request.args.get("maxDistanceMeter", 2000))
    route_option_count = int(request.args.get("routeOptionCount", 2))

    sync_result = sync_from_ta_server() if sync else None
    sensors = cached_sensors()
    route_sensors = cached_sensors(collectible=True)
    routes = cached_routes(
        sensors=route_sensors,
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        max_distance_meter=max_distance_meter,
        route_option_count=route_option_count,
    )
    return success(
        {
            "sync": sync_result,
            "weather": weather_payload("ALL", sensors),
            "routes": routes,
            "reward": db.reward_summary(user_id),
        },
        "앱 초기 데이터 조회에 성공했습니다.",
    )


@app.post("/api/v1/sensor-readings")
def upload_sensor_reading():
    payload = request.get_json(force=True)
    required = [
        "userId",
        "temperature",
        "co2",
        "latitude",
        "longitude",
        "collectedAt",
    ]
    missing = [key for key in required if key not in payload]
    if "sensorName" not in payload and "sensorId" not in payload:
        missing.append("sensorName or sensorId")
    if missing:
        return failure(f"필수 요청 데이터가 누락되었습니다: {', '.join(missing)}", 400)

    data = db.insert_sensor_reading(payload)
    clear_read_cache()
    return success(data, "센서 데이터가 저장되었습니다.")


@app.get("/api/v1/weather/sensors")
def weather_sensors():
    target = request.args.get("target", "ALL")
    sensors = cached_sensors(collectible=True)
    if target != "ALL":
        sensors = [
            sensor
            for sensor in sensors
            if sensor["sensor_id"] == target or sensor["sensor_name"] == target
        ]

    return success(
        weather_payload(target, sensors),
        "센서 날씨 데이터 조회에 성공했습니다.",
    )


@app.post("/api/v1/routes/recommendations")
def route_recommendations():
    payload = request.get_json(force=True)
    current_latitude = float(payload["currentLatitude"])
    current_longitude = float(payload["currentLongitude"])
    max_distance_meter = int(payload.get("maxDistanceMeter", 3500))
    route_option_count = int(payload.get("routeOptionCount", 2))

    sensors = cached_sensors(collectible=True)
    routes = cached_routes(
        sensors=sensors,
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        max_distance_meter=max_distance_meter,
        route_option_count=route_option_count,
    )
    result = {"routes": compact_routes_for_app(routes)}
    recommendation_log = {
        "routes": [
            {
                "routeId": route["routeId"],
                "routeName": route["routeName"],
                "routeProfile": route.get("routeProfile"),
                "estimatedDistanceMeter": route["estimatedDistanceMeter"],
                "estimatedTimeMinute": route["estimatedTimeMinute"],
                "sensorIds": [sensor["sensorId"] for sensor in route["sensors"]],
                "targetSensorId": route["targetSensor"]["sensorId"],
            }
            for route in routes
        ]
    }
    db.save_route_recommendation(
        user_id=payload.get("userId"),
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        max_distance_meter=max_distance_meter,
        route_option_count=route_option_count,
        payload=recommendation_log,
    )
    return success(result, "추천 경로가 생성되었습니다.")


@app.post("/api/v1/missions/results")
def mission_results():
    payload = request.get_json(force=True)
    data = db.record_mission_result(payload)
    reward = db.reward_summary(payload["userId"])
    data["myDailyRank"] = reward["myRanking"]["rank"]
    return success(data, "미션 결과가 저장되었습니다.")


@app.get("/api/v1/rewards/summary")
def rewards_summary():
    user_id = request.args.get("userId", "USER_001")
    return success(db.reward_summary(user_id), "포인트 및 랭킹 조회에 성공했습니다.")


@app.post("/api/v1/rewards/exchange")
def rewards_exchange():
    payload = request.get_json(force=True)
    try:
        data = db.exchange_reward(
            user_id=payload.get("userId", "USER_001"),
            point_cost=int(payload["pointCost"]),
            reward_won=int(payload["rewardWon"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        return failure(str(exc), 400)
    return success(data, "보상 교환이 완료되었습니다.")


@app.post("/api/v1/auth/login")
def login():
    payload = request.get_json(force=True)
    user = db.get_user_by_login(payload.get("loginId", ""), payload.get("password", ""))
    if not user:
        return failure("로그인 정보가 올바르지 않습니다.", 401)
    return success(
        {
            "userId": user["user_id"],
            "nickname": user["nickname"],
            "accessToken": f"sample-token-{user['user_id']}",
        },
        "로그인에 성공했습니다.",
    )


@app.post("/api/v1/auth/register")
def register():
    payload = request.get_json(force=True)
    try:
        user = db.create_user(
            login_id=payload.get("loginId", ""),
            raw_password=payload.get("password", ""),
            nickname=payload.get("nickname"),
        )
    except ValueError as exc:
        return failure(str(exc), 400)
    return success(
        {
            "userId": user["user_id"],
            "nickname": user["nickname"],
            "accessToken": f"sample-token-{user['user_id']}",
        },
        "회원가입이 완료되었습니다.",
    )


@app.get("/api/walk/recommendation")
def legacy_walk_recommendation():
    sync_from_ta_server()
    weather = weather_payload("ALL", db.list_sensors_with_counts())
    co2_average = weather["average"]["co2"]
    if co2_average >= 800:
        level = "나쁨"
    elif co2_average >= 600:
        level = "보통"
    else:
        level = "좋음"
    return jsonify(
        {
            "recommendation_level": level,
            "co2_average": co2_average,
            "source": "firebase",
        }
    )


@app.post("/api/route/generate")
def legacy_route_generate():
    payload = request.get_json(force=True)
    current_latitude = float(payload.get("current_latitude") or payload.get("currentLatitude"))
    current_longitude = float(payload.get("current_longitude") or payload.get("currentLongitude"))
    routes = recommend_routes(
        sensors=db.list_collectible_sensors_with_counts(),
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        max_distance_meter=int(payload.get("max_distance_meter") or payload.get("maxDistanceMeter") or 3500),
        route_option_count=1,
    )
    first_sensor = routes[0]["sensors"][0] if routes and routes[0]["sensors"] else None
    return jsonify(
        {
            "target_shortage_sensor": first_sensor["sensorName"] if first_sensor else "",
            "route": routes[0] if routes else None,
            "source": "firebase",
        }
    )


@app.post("/api/sensor/report")
def legacy_sensor_report():
    payload = request.get_json(force=True)
    sensor_name = payload.get("detected_sensor_name") or payload.get("sensorName") or "sensor 01"
    sensor_id = sensor_id_from_name(sensor_name)
    reading = db.insert_sensor_reading(
        {
            "userId": payload.get("userId", "USER_001"),
            "sensorId": sensor_id,
            "sensorName": sensor_name,
            "temperature": payload.get("measured_temp", payload.get("temperature", 24.0)),
            "co2": payload.get("measured_co2", payload.get("co2", 530)),
            "latitude": payload.get("latitude", 36.628123),
            "longitude": payload.get("longitude", 127.457891),
            "rssi": payload.get("rssi_signal_dbm", payload.get("rssi", -65)),
            "collectedAt": payload.get("collectedAt"),
        }
    )
    mission = db.record_mission_result(
        {
            "userId": payload.get("userId", "USER_001"),
            "startedSensorId": sensor_id,
            "missionType": "ONE_MINUTE_GAME",
            "score": int(payload.get("score", 15)),
        }
    )
    return jsonify(
        {
            "reading_id": reading["readingId"],
            "allocated_reward_point": mission["earnedPoint"],
            "total_point": mission["totalPoint"],
            "source": "firebase",
        }
    )


def sync_from_ta_server() -> dict[str, Any]:
    if not official_sync_enabled():
        local_sensors = cached_sensors()
        return {
            "syncedSensorCount": 0,
            "storedReadingCount": 0,
            "freshSensorCount": sum(1 for sensor in local_sensors if sensor["fresh"]),
            "syncedAt": None,
            "source": "firebase-cache",
            "message": "Official sensor sync is disabled; using cached Firebase data.",
        }

    try:
        sensors = fetch_official_sensors()
        result = db.sync_official_sensors(sensors)
        clear_read_cache()
        result["source"] = "ta-server"
        return result
    except Exception as exc:
        local_sensors = cached_sensors()
        return {
            "syncedSensorCount": 0,
            "storedReadingCount": 0,
            "freshSensorCount": sum(1 for sensor in local_sensors if sensor["fresh"]),
            "syncedAt": None,
            "source": "firebase-cache",
            "error": str(exc),
        }


def weather_payload(target: str, sensors: list[dict[str, Any]]) -> dict[str, Any]:
    average_temperature = (
        round(mean([sensor["temperature"] or 0 for sensor in sensors]), 1) if sensors else 0.0
    )
    average_co2 = round(mean([sensor["co2"] or 0 for sensor in sensors])) if sensors else 0
    return {
        "target": target,
        "average": {
            "temperature": average_temperature,
            "co2": average_co2,
        },
        "sensorCount": len(sensors),
        "sensors": [sensor_response(sensor) for sensor in sensors],
    }


def sensor_response(sensor: dict[str, Any]) -> dict[str, Any]:
    return {
        "sensorId": sensor["sensor_id"],
        "sensorName": sensor["sensor_name"],
        "latitude": sensor["latitude"],
        "longitude": sensor["longitude"],
        "routeLatitude": sensor["route_latitude"],
        "routeLongitude": sensor["route_longitude"],
        "missionRadiusMeter": sensor["mission_radius_meter"],
        "routeSnapDistanceMeter": sensor["route_snap_distance_meter"],
        "temperature": sensor["temperature"],
        "co2": sensor["co2"],
        "fresh": bool(sensor["fresh"]),
        "measuredAt": sensor["measured_at"],
        "updatedAt": sensor["measured_at"],
        "collectedCountToday": sensor["collected_count_today"],
    }


def success(data: dict[str, Any], message: str):
    return json_response({"success": True, "data": data, "message": message})


def failure(message: str, status_code: int):
    return json_response({"success": False, "data": None, "message": message}, status_code)


def json_response(payload: dict[str, Any], status_code: int = 200):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return Response(body, status=status_code, mimetype="application/json")


def compact_routes_for_app(routes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "routeId": route["routeId"],
            "routeName": route["routeName"],
            "routeStrategy": route["routeStrategy"],
            "routeProfile": route.get("routeProfile"),
            "estimatedDistanceMeter": route["estimatedDistanceMeter"],
            "estimatedTimeMinute": route["estimatedTimeMinute"],
            "routePoints": [
                [
                    round(float(point["latitude"]), 6),
                    round(float(point["longitude"]), 6),
                ]
                for point in route["routePoints"]
            ],
            "sensors": [compact_sensor_for_app(sensor) for sensor in route["sensors"]],
            "targetSensor": compact_sensor_for_app(route["targetSensor"]),
            "average": route["average"],
        }
        for route in routes
    ]


def compact_sensor_for_app(sensor: dict[str, Any]) -> dict[str, Any]:
    return {
        "sensorId": sensor["sensorId"],
        "sensorName": sensor["sensorName"],
        "latitude": sensor["latitude"],
        "longitude": sensor["longitude"],
        "routeLatitude": sensor["routeLatitude"],
        "routeLongitude": sensor["routeLongitude"],
        "missionRadiusMeter": sensor["missionRadiusMeter"],
        "collectedCountToday": sensor["collectedCountToday"],
        "visitOrder": sensor["visitOrder"],
        "isTarget": sensor["isTarget"],
    }


if __name__ == "__main__":
    debug = os.environ.get("FLASK_DEBUG", "false").lower() == "true"
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=debug)
