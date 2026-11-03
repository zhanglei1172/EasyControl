# Web and agent control

The controller app starts a small LAN HTTP server on port `18080` while its main screen is open. Open `http://<controller-phone-ip>:18080/` from a phone browser on the same Wi-Fi. The page lists saved devices, polls screenshots, and sends touch, swipe, Back, and Home actions.

The same endpoints are usable from an agent or CLI:

```sh
BASE=http://192.168.1.20:18080
UUID='device-uuid-from-api-devices'
curl "$BASE/api/devices"
curl "$BASE/api/screenshot?uuid=$UUID" -o screen.png
curl -X POST "$BASE/api/touch" -H 'Content-Type: application/json' \
  -d "{\"uuid\":\"$UUID\",\"x\":500,\"y\":900}"
curl -X POST "$BASE/api/swipe" -H 'Content-Type: application/json' \
  -d "{\"uuid\":\"$UUID\",\"x1\":500,\"y1\":1600,\"x2\":500,\"y2\":400,\"duration\":350}"
curl -X POST "$BASE/api/key" -H 'Content-Type: application/json' \
  -d "{\"uuid\":\"$UUID\",\"keycode\":4}"
```

Coordinates use the controlled device's physical screenshot coordinates. The bridge is intended for a trusted LAN; it currently has no authentication, so do not expose port `18080` to the public Internet. The app must be running in the foreground at least once to start the bridge.
