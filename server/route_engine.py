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
ROUTE_DISTANCE_BUDGET_MULTIPLIER = 1.5
DEFAULT_MAX_ROUTE_DISTANCE_METER = 3500
ROUTE_SEARCH_BEAM_WIDTH = 420


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


@dataclass(frozen=True)
class RouteSearchState:
    sensor_indices: tuple[int, ...]
    length_meter: int


@dataclass(frozen=True)
class RouteDistanceProfile:
    key: str
    name: str
    min_meter: int
    max_meter: int


def recommend_routes(
    sensors: list[dict[str, Any]],
    current_latitude: float,
    current_longitude: float,
    max_distance_meter: int = DEFAULT_MAX_ROUTE_DISTANCE_METER,
    route_option_count: int = 2,
) -> list[dict[str, Any]]:
    current_access_point = snap_sensor_to_access_point(current_latitude, current_longitude)
    route_start_latitude = float(current_access_point["latitude"])
    route_start_longitude = float(current_access_point["longitude"])
    profiles = route_distance_profiles(max_distance_meter)[:route_option_count]
    search_max_meter = max(profile.max_meter for profile in profiles)
    candidates = []
    for sensor in sensors:
        latitude = float(sensor["latitude"])
        longitude = float(sensor["longitude"])
        access_point = sensor_access_point(sensor, latitude, longitude)
        distance = walking_distance_meter(
            route_start_latitude,
            route_start_longitude,
            access_point["routeLatitude"],
            access_point["routeLongitude"],
        )
        if distance > search_max_meter:
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

    candidates = unique_candidates_by_access_point(candidates)
    if not candidates:
        return []

    candidates = sorted(candidates, key=shortage_sort_key)
    route_states = route_states_for_distance_profiles(
        current_latitude=route_start_latitude,
        current_longitude=route_start_longitude,
        candidates=candidates,
        max_distance_meter=search_max_meter,
    )
    if not route_states:
        return []

    routes = []
    used_sensor_ids: set[str] = set()
    used_access_points: set[tuple[float, float]] = set()
    used_path_points: set[tuple[float, float]] = set()
    used_paths: set[tuple[int, ...]] = set()

    for profile in profiles:
        state = select_route_state_for_profile(
            states=route_states,
            candidates=candidates,
            profile=profile,
            used_sensor_ids=used_sensor_ids,
            used_access_points=used_access_points,
            used_path_points=used_path_points,
            used_paths=used_paths,
            current_latitude=current_latitude,
            current_longitude=current_longitude,
        )
        if state is None:
            continue

        route_sensors = [candidates[index] for index in state.sensor_indices]
        target_sensor = route_sensors[-1]
        direct_distance = walking_distance_meter(
            route_start_latitude,
            route_start_longitude,
            target_sensor.route_latitude,
            target_sensor.route_longitude,
        )
        routes.append(
            format_route(
                route_index=len(routes),
                current_latitude=current_latitude,
                current_longitude=current_longitude,
                route_sensors=route_sensors,
                target_sensor=target_sensor,
                direct_distance_meter=direct_distance,
                distance_budget_meter=profile.max_meter,
                route_strategy="distance_band_shortage_overlap",
                route_name=profile.name,
                route_profile=profile,
            )
        )
        used_paths.add(state.sensor_indices)
        used_sensor_ids.update(sensor.sensor_id for sensor in route_sensors)
        used_access_points.update(access_point_key(sensor) for sensor in route_sensors)
        used_path_points.update(
            route_point_keys(
                current_latitude=current_latitude,
                current_longitude=current_longitude,
                route_sensors=route_sensors,
            )
        )

    return routes


def route_distance_profiles(max_distance_meter: int) -> list[RouteDistanceProfile]:
    long_max_meter = max(max_distance_meter, 2500)
    return [
        RouteDistanceProfile("short", "500m~1km 토템 산책", 500, 1000),
        RouteDistanceProfile("medium", "1km~2km 토템 산책", 1000, 2000),
        RouteDistanceProfile("long", "2km 이상 토템 산책", 2000, long_max_meter),
    ]


def shortage_sort_key(sensor: RouteCandidate) -> tuple[int, int, int, str]:
    return (
        sensor.collected_count_today,
        0 if not sensor.fresh else 1,
        sensor.distance_meter,
        sensor.sensor_name,
    )


def unique_candidates_by_access_point(candidates: list[RouteCandidate]) -> list[RouteCandidate]:
    unique: dict[tuple[float, float], RouteCandidate] = {}
    for candidate in sorted(candidates, key=shortage_sort_key):
        unique.setdefault(access_point_key(candidate), candidate)
    return list(unique.values())


def route_states_for_distance_profiles(
    current_latitude: float,
    current_longitude: float,
    candidates: list[RouteCandidate],
    max_distance_meter: int,
) -> list[RouteSearchState]:
    empty_state = RouteSearchState(sensor_indices=(), length_meter=0)
    if not candidates:
        return []

    distance_cache: dict[tuple[str, str], int] = {}

    def node_coordinate(index: int | str) -> tuple[float, float]:
        if index == "start":
            return current_latitude, current_longitude
        sensor = candidates[int(index)]
        return sensor.route_latitude, sensor.route_longitude

    def path_distance(first: int | str, second: int | str) -> int:
        cache_key = (str(first), str(second))
        reverse_key = (cache_key[1], cache_key[0])
        if cache_key in distance_cache:
            return distance_cache[cache_key]
        if reverse_key in distance_cache:
            return distance_cache[reverse_key]

        first_latitude, first_longitude = node_coordinate(first)
        second_latitude, second_longitude = node_coordinate(second)
        distance = walking_distance_meter(
            first_latitude,
            first_longitude,
            second_latitude,
            second_longitude,
        )
        distance_cache[cache_key] = distance
        return distance

    def state_value(state: RouteSearchState) -> int:
        return route_shortage_score(state, candidates)

    def beam_sort_key(state: RouteSearchState) -> tuple[int, int, int, int, tuple[int, ...]]:
        return (
            -len(state.sensor_indices),
            -state_value(state),
            abs(1800 - state.length_meter),
            state.length_meter,
            state.sensor_indices,
        )

    beam = [empty_state]
    best_by_path: dict[tuple[int, ...], RouteSearchState] = {}

    for _ in range(len(candidates)):
        expanded_states: list[RouteSearchState] = []
        for state in beam:
            used_indices = set(state.sensor_indices)
            last_node: int | str = "start" if not state.sensor_indices else state.sensor_indices[-1]
            for candidate_index in range(len(candidates)):
                if candidate_index in used_indices:
                    continue

                next_length = state.length_meter + path_distance(last_node, candidate_index)
                if next_length > max_distance_meter:
                    continue

                next_path = state.sensor_indices + (candidate_index,)
                previous_best = best_by_path.get(next_path)
                if previous_best and previous_best.length_meter <= next_length:
                    continue

                next_state = RouteSearchState(
                    sensor_indices=next_path,
                    length_meter=next_length,
                )
                best_by_path[next_path] = next_state
                expanded_states.append(next_state)

        if not expanded_states:
            break

        beam = sorted(
            [*beam, *expanded_states],
            key=beam_sort_key,
        )[:ROUTE_SEARCH_BEAM_WIDTH]

    return sorted(best_by_path.values(), key=beam_sort_key)


def select_route_state_for_profile(
    states: list[RouteSearchState],
    candidates: list[RouteCandidate],
    profile: RouteDistanceProfile,
    used_sensor_ids: set[str],
    used_access_points: set[tuple[float, float]],
    used_path_points: set[tuple[float, float]],
    used_paths: set[tuple[int, ...]],
    current_latitude: float,
    current_longitude: float,
) -> RouteSearchState | None:
    selectable_states = [
        state
        for state in states
        if state.sensor_indices and state.sensor_indices not in used_paths
    ]
    if not selectable_states:
        return None

    def distance_penalty(length_meter: int) -> int:
        if profile.min_meter <= length_meter <= profile.max_meter:
            midpoint = (profile.min_meter + profile.max_meter) // 2
            return abs(length_meter - midpoint)
        if length_meter < profile.min_meter:
            return profile.min_meter - length_meter
        return length_meter - profile.max_meter

    def state_sort_key(state: RouteSearchState) -> tuple[int, int, int, int, int, int, tuple[int, ...]]:
        route_sensors = [candidates[index] for index in state.sensor_indices]
        sensor_ids = {sensor.sensor_id for sensor in route_sensors}
        access_points = {access_point_key(sensor) for sensor in route_sensors}
        in_band = profile.min_meter <= state.length_meter <= profile.max_meter
        return (
            0 if in_band else 1,
            len(sensor_ids & used_sensor_ids),
            len(access_points & used_access_points),
            -route_shortage_score(state, candidates),
            -len(state.sensor_indices),
            distance_penalty(state.length_meter),
            state.sensor_indices,
        )

    return min(selectable_states, key=state_sort_key)


def route_shortage_score(state: RouteSearchState, candidates: list[RouteCandidate]) -> int:
    if not state.sensor_indices:
        return 0
    max_collected_count = max(candidate.collected_count_today for candidate in candidates) + 1
    score = 0
    for index in state.sensor_indices:
        sensor = candidates[index]
        score += max(1, max_collected_count - sensor.collected_count_today) * 10
        score += 8 if not sensor.fresh else 0
        score += 15
    return score


def route_point_keys(
    current_latitude: float,
    current_longitude: float,
    route_sensors: list[RouteCandidate],
) -> set[tuple[float, float]]:
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
    return {
        (round(point["latitude"], 5), round(point["longitude"], 5))
        for point in route_points
    }


def access_point_key(sensor: RouteCandidate) -> tuple[float, float]:
    return (round(sensor.route_latitude, 7), round(sensor.route_longitude, 7))


def format_route(
    route_index: int,
    current_latitude: float,
    current_longitude: float,
    route_sensors: list[RouteCandidate],
    target_sensor: RouteCandidate,
    direct_distance_meter: int,
    distance_budget_meter: int,
    route_strategy: str,
    route_name: str | None = None,
    route_profile: RouteDistanceProfile | None = None,
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
        "routeName": route_name or default_route_name(route_index),
        "routeStrategy": route_strategy,
        "routeProfile": {
            "key": route_profile.key,
            "minDistanceMeter": route_profile.min_meter,
            "maxDistanceMeter": route_profile.max_meter,
        } if route_profile else None,
        "directDistanceMeter": direct_distance_meter,
        "distanceBudgetMeter": distance_budget_meter,
        "budgetMultiplier": ROUTE_DISTANCE_BUDGET_MULTIPLIER,
        "intermediateSensorCount": max(0, len(route_sensors) - 1),
        "estimatedDistanceMeter": estimated_distance,
        "estimatedTimeMinute": estimated_time,
        "routePoints": route_points,
        "sensors": [
            sensor_payload(sensor, index + 1, sensor.sensor_id == target_sensor.sensor_id)
            for index, sensor in enumerate(route_sensors)
        ],
        "targetSensor": sensor_payload(target_sensor, len(route_sensors), True),
        "average": {
            "temperature": round(average_temperature, 1),
            "co2": average_co2,
        },
    }


def default_route_name(route_index: int) -> str:
    names = [
        "최대 경유 센서 경로",
        "짧은 보강 대안 경로",
        "추가 경유 대안 경로",
    ]
    if route_index < len(names):
        return names[route_index]
    return f"추천 경로 {route_index + 1}"


def sensor_payload(sensor: RouteCandidate, visit_order: int, is_target: bool) -> dict[str, Any]:
    return {
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
        "visitOrder": visit_order,
        "isTarget": is_target,
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
