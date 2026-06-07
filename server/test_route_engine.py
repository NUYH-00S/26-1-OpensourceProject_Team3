import unittest

from route_engine import recommend_routes
from route_geometry import WALKWAY_NODES


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
    def campus_graph_sensors(self) -> list[dict]:
        node_names = [
            "n15_mid",
            "central_cross",
            "culture_s",
            "culture_e",
            "education_e",
            "education_n",
            "n15_east",
            "social_east",
            "social_mid",
            "social_west",
            "upper_west",
            "upper_mid",
            "west_mid",
            "west_library",
            "west_east",
            "west_cross",
            "central_south",
            "museum_east",
            "museum_mid",
            "museum_west",
            "sw_cross",
            "sw_corner",
        ]
        sensors = []
        for index, node_name in enumerate(node_names):
            latitude, longitude = WALKWAY_NODES[node_name]
            sensors.append(
                sensor(
                    f"SENSOR_{index:03d}",
                    f"sensor {index:03d}",
                    latitude,
                    longitude,
                    index % 10,
                    fresh=index % 4 != 0,
                )
            )
        return sensors

    def test_routes_cover_three_distance_bands_with_limited_overlap(self):
        routes = recommend_routes(
            sensors=self.campus_graph_sensors(),
            current_latitude=36.628123,
            current_longitude=127.457891,
            max_distance_meter=3500,
            route_option_count=3,
        )

        self.assertEqual(3, len(routes))
        self.assertEqual(["short", "medium", "long"], [route["routeProfile"]["key"] for route in routes])

        expected_ranges = [(500, 1000), (1000, 2000), (2000, 3500)]
        for route, (minimum, maximum) in zip(routes, expected_ranges):
            self.assertGreaterEqual(route["estimatedDistanceMeter"], minimum)
            self.assertLessEqual(route["estimatedDistanceMeter"], maximum)
            self.assertEqual(route["targetSensor"]["sensorId"], route["sensors"][-1]["sensorId"])
            self.assertTrue(route["sensors"][-1]["isTarget"])

        sensor_sets = [
            {sensor_payload["sensorId"] for sensor_payload in route["sensors"]}
            for route in routes
        ]
        for first_index, first_set in enumerate(sensor_sets):
            for second_set in sensor_sets[first_index + 1:]:
                self.assertLessEqual(len(first_set & second_set), 1)

    def test_sensor_count_is_not_capped_at_three_when_distance_budget_allows_more(self):
        routes = recommend_routes(
            sensors=self.campus_graph_sensors(),
            current_latitude=36.628123,
            current_longitude=127.457891,
            max_distance_meter=3500,
            route_option_count=1,
        )

        self.assertEqual(1, len(routes))
        self.assertGreater(len(routes[0]["sensors"]), 3)
        self.assertGreaterEqual(routes[0]["estimatedDistanceMeter"], 500)
        self.assertLessEqual(routes[0]["estimatedDistanceMeter"], 1000)


if __name__ == "__main__":
    unittest.main()
