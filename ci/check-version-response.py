#!/usr/bin/env python3
"""Wait for a packaged app, then assert its public build-version HTTP contract."""
import json
import sys
import time
import urllib.error
import urllib.request

url, expected = sys.argv[1:]
deadline = time.monotonic() + 60
while True:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            assert response.status == 200, response.status
            assert response.headers.get_content_type() == "application/json", response.headers
            assert response.headers.get("Cache-Control") == "no-store", response.headers
            body = json.load(response)
            assert body == {"version": expected}, (body, expected)
        print(f"{url}: {expected}")
        break
    except (urllib.error.URLError, TimeoutError):
        if time.monotonic() >= deadline:
            raise
        time.sleep(0.25)
