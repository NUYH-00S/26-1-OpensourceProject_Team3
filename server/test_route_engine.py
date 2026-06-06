import unittest

from route_engine import ROUTE_DISTANCE_BUDGET_MULTIPLIER, recommend_routes
from route_geometry import build_route_points, snap_sensor_to_access_point


def sensor(
    sensor_id: str,
    sensor_name: str,
    latitude: float,
    longitude: float,
    collected_count_today: int,
    fresh: bool = True,
) -> dict:
    return {
        "sensor_id": sensor_id,
        "sensor_name": sensor_name,
        "latitude": latitude,
        "longitude": longitude,
        "temperature": 24.0,
        "co2": 600,
        "fresh": fresh,
        "measured_at": "2026-06-06T00:00:00Z",
        "collected_count_today": collected_count_today,
    }


class RouteEngineTest(unittest.TestCase):
    def test_routes_target_most_shortage_sensor_within_one_point_five_budget(self):
        routes = recommend_routes(
            sensors=[
                sensor("SENSOR_019", "sensor 19", 36.627597, 127.455668, 0, fresh=False),
                sensor("SENSOR_001", "sensor 01", 36.627794, 127.458035, 5),
                sensor("SENSOR_002", "sensor 02", 36.627959, 127.457050, 6),
                sensor("SENSOR_003", "sensor 03", 36.628193, 127.455896, 7),
                sensor("SENSOR_012", "sensor 12", 36.627453, 127.455976, 8),
                sensor("SENSOR_016", "sensor 16", 36.628021, 127.454927, 9),
            ],
            current_latitude=36.628123,
            current_longitude=127.457891,
            max_distance_meter=2000,
            route_option_count=3,
        )

        self.assertEqual(3, len(routes))
        sensor_counts = [len(route["sensors"]) for route in routes]
        self.assertEqual(sensor_counts, sorted(sensor_counts, reverse=True))

        for route in routes:
            self.assertEqual("SENSOR_019", route["targetSensor"]["sensorId"])
            self.assertTrue(route["sensors"][-1]["isTarget"])
            self.assertEqual("SENSOR_019", route["sensors"][-1]["sensorId"])
            self.assertLessEqual(
                route["estimatedDistanceMeter"],
                route["distanceBudgetMeter"],
            )
            self.assertLessEqual(
                route["estimatedDistanceMeter"],
                round(route["directDistanceMeter"] * ROUTE_DISTANCE_BUDGET_MULTIPLIER),
            )

        self.assertGreater(routes[0]["intermediateSensorCount"], 0)

    def test_sensor_count_is_not_capped_at_three_when_distance_budget_allows_more(self):
        start = (36.628123, 127.457891)
        target = sensor("SENSOR_TARGET", "target sensor", 36.627597, 127.455668, 0, fresh=False)
        target_access = snap_sensor_to_access_point(target["latitude"], target["longitude"])
        direct_points = build_route_points(
            current_latitude=start[0],
            current_longitude=start[1],
            route_stops=[
                {
                    "routeLatitude": target_access["latitude"],
                    "routeLongitude": target_access["longitude"],
                }
            ],
        )
        intermediate_points = direct_points[1:6]
        sensors = [
            target,
            *[
                sensor(
                    f"SENSOR_PATH_{index:03d}",
                    f"path sensor {index}",
                    point["latitude"],
                    point["longitude"],
                    index + 1,
                )
                for index, point in enumerate(intermediate_points, start=1)
            ],
        ]

        routes = recommend_routes(
            sensors=sensors,
            current_latitude=start[0],
            current_longitude=start[1],
            max_distance_meter=2000,
            route_option_count=1,
        )

        self.assertEqual(1, len(routes))
        self.assertGreater(len(routes[0]["sensors"]), 3)
        self.assertEqual(len(sensors), len(routes[0]["sensors"]))
        self.assertLessEqual(
            routes[0]["estimatedDistanceMeter"],
            routes[0]["distanceBudgetMeter"],
        )
        self.assertEqual("SENSOR_TARGET", routes[0]["sensors"][-1]["sensorId"])


if __name__ == "__main__":
    unittest.main()
