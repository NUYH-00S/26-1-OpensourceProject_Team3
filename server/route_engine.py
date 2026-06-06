from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from route_geometry import (
    DEFAULT_MISSION_RADIUS_METER,
    build_route_points,
    route_distance_meter,
    snap_sensor_to_access_point,
    walking_distance_meter,
)


WALKING_SPEED_METER_PER_MINUTE = 72.0


@dataclass(frozen=True)
class RouteCandidate:
    sensor_id: str
    sensor_name: str
    latitude: float
    longitude: float
    route_latitude: float
    route_longitude: float
    mission_radius_meter: int
    route_snap_distance_meter: int
    temperature: float
    co2: int
    fresh: bool
    measured_at: str | None
    collected_count_today: int
    distance_meter: int


def recommend_routes(
    sensors: list[dict[str, Any]],
    current_latitude: float,
    current_longitude: float,
    max_distance_meter: int = 2000,
    route_option_count: int = 2,
    sensors_per_route: int = 3,
) -> list[dict[str, Any]]:
    candidates = []
    for sensor in sensors:
        latitude = float(sensor["latitude"])
        longitude = float(sensor["longitude"])
        access_point = sensor_access_point(sensor, latitude, longitude)
        distance = walking_distance_meter(
            current_latitude,
            current_longitude,
            access_point["routeLatitude"],
            access_point["routeLongitude"],
        )
        if distance > max_distance_meter:
            continue
        candidates.append(
            RouteCandidate(
                sensor_id=sensor["sensor_id"],
                sensor_name=sensor["sensor_name"],
                latitude=latitude,
                longitude=longitude,
                route_latitude=access_point["routeLatitude"],
                route_longitude=access_point["routeLongitude"],
                mission_radius_meter=access_point["missionRadiusMeter"],
                route_snap_distance_meter=access_point["routeSnapDistanceMeter"],
                temperature=float(sensor.get("temperature") or 0.0),
                co2=int(sensor.get("co2") or 0),
                fresh=bool(sensor.get("fresh")),
                measured_at=sensor.get("measured_at"),
                collected_count_today=int(sensor.get("collected_count_today") or 0),
                distance_meter=distance,
            )
        )

    shortage_order = sorted(
        candidates,
        key=lambda item: (
            item.collected_count_today,
            0 if not item.fresh else 1,
            item.distance_meter,
            item.sensor_name,
        ),
    )
    unused = shortage_order[: max(route_option_count * sensors_per_route * 2, sensors_per_route)]
    routes = []

    for route_index in range(route_option_count):
        if not unused:
            break

        first = unused.pop(0)
        route_sensors = [first]
        used_access_points = {access_point_key(first)}
        last_lat = first.route_latitude
        last_lon = first.route_longitude

        while unused and len(route_sensors) < sensors_per_route:
            selectable = [
                sensor
                for sensor in unused
                if access_point_key(sensor) not in used_access_points
            ] or unused
            next_sensor = min(
                selectable,
                key=lambda item: (
                    walking_distance_meter(last_lat, last_lon, item.route_latitude, item.route_longitude),
                    item.collected_count_today,
                    item.sensor_name,
                ),
            )
            unused.remove(next_sensor)
            route_sensors.append(next_sensor)
            used_access_points.add(access_point_key(next_sensor))
            last_lat = next_sensor.route_latitude
            last_lon = next_sensor.route_longitude

        routes.append(
            format_route(
                route_index=route_index,
                current_latitude=current_latitude,
                current_longitude=current_longitude,
                route_sensors=route_sensors,
            )
        )

    return routes


def access_point_key(sensor: RouteCandidate) -> tuple[float, float]:
    return (round(sensor.route_latitude, 7), round(sensor.route_longitude, 7))


def format_route(
    route_index: int,
    current_latitude: float,
    current_longitude: float,
    route_sensors: list[RouteCandidate],
) -> dict[str, Any]:
    route_points = build_route_points(
        current_latitude=current_latitude,
        current_longitude=current_longitude,
        route_stops=[
            {
                "routeLatitude": sensor.route_latitude,
                "routeLongitude": sensor.route_longitude,
            }
            for sensor in route_sensors
        ],
    )
    estimated_distance = route_distance_meter(route_points)
    estimated_time = max(5, int(round(estimated_distance / WALKING_SPEED_METER_PER_MINUTE)))
    average_temperature = (
        sum(sensor.temperature for sensor in route_sensors) / len(route_sensors)
        if route_sensors
        else 0.0
    )
    average_co2 = (
        int(round(sum(sensor.co2 for sensor in route_sensors) / len(route_sensors)))
        if route_sensors
        else 0
    )

    return {
        "routeId": f"ROUTE_{route_index + 1:03d}",
        "routeName": "데이터 부족 센서 우선 경로" if route_index == 0 else "근거리 보강 경로",
        "estimatedDistanceMeter": estimated_distance,
        "estimatedTimeMinute": estimated_time,
        "routePoints": route_points,
        "sensors": [
            {
                "sensorId": sensor.sensor_id,
                "sensorName": sensor.sensor_name,
                "latitude": sensor.latitude,
                "longitude": sensor.longitude,
                "routeLatitude": sensor.route_latitude,
                "routeLongitude": sensor.route_longitude,
                "missionRadiusMeter": sensor.mission_radius_meter,
                "routeSnapDistanceMeter": sensor.route_snap_distance_meter,
                "temperature": sensor.temperature,
                "co2": sensor.co2,
                "fresh": sensor.fresh,
                "measuredAt": sensor.measured_at,
                "collectedCountToday": sensor.collected_count_today,
                "distanceMeter": sensor.distance_meter,
                "visitOrder": index + 1,
            }
            for index, sensor in enumerate(route_sensors)
        ],
        "average": {
            "temperature": round(average_temperature, 1),
            "co2": average_co2,
        },
    }


def sensor_access_point(sensor: dict[str, Any], latitude: float, longitude: float) -> dict[str, Any]:
    mission_radius_meter = int(
        sensor.get("mission_radius_meter", sensor.get("missionRadiusMeter", DEFAULT_MISSION_RADIUS_METER))
        or DEFAULT_MISSION_RADIUS_METER
    )

    snapped = snap_sensor_to_access_point(latitude, longitude)
    return {
        "routeLatitude": float(snapped["latitude"]),
        "routeLongitude": float(snapped["longitude"]),
        "missionRadiusMeter": mission_radius_meter,
        "routeSnapDistanceMeter": int(snapped["snapDistanceMeter"]),
    }
