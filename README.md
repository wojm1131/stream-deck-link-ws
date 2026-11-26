# Stream Deck Link WS

A RuneLite plugin that broadcasts local player state over a **WebSocket**
so the companion **RuneLite Stats** Elgato Stream Deck plugin can display
it on Stream Deck keys: skills, activity, movement, FPS, Grand Exchange
offers — and Sailing crew / salvaging-hook state.

It is the successor to the original *Stream Deck Link* plugin, which
served the same kind of state from an HTTP endpoint and was declined by
the Plugin Hub for that transport ("We are not currently accepting
plugins that expose player information over HTTP", runelite/plugin-hub
#21332). This plugin is the design response to that decision. (The
hub's existing grandfathered HTTP Server plugin is not an alternative
source for this data either: it exposes only stats, inventory and
equipment — no activity, GE, or crew surface.)

## Design

- **WebSocket broadcast, not an HTTP API.** The plugin runs a small
  hand-rolled RFC 6455 WebSocket server (JDK only, no dependencies)
  bound to **127.0.0.1 only**, on ports **42024**, **43061**, **43021**
  — the first that binds wins. The ports deliberately differ from
  ChekTek's Rune Deck (42023/43060/43020) so both plugins can run at
  the same time.
- **Push on change.** State is checked every game tick; a payload is
  broadcast only when it changes. Every newly connected client first
  receives the full current state.
- **Read-only, with no command channel.** The server completes the
  handshake, sends text frames, and answers ping/close. It never acts
  on the content of any frame a client sends — received payloads are
  read off the socket and discarded. Nothing a connected program sends
  can affect the game or the plugin.
- **Same payload shapes as Rune Deck** for the shared types, so one
  Stream Deck client can read either source.

## Payloads

```jsonc
{ "type": "SKILLS", "currentHealth": 83, "totalHealth": 99, /* …all 24 skills… */ "combatLevel": 90, "currentSailing": 71, "totalSailing": 71 }
{ "type": "ACTIVITY", "isActive": true }              // animating / interacting / moving, 3-tick idle debounce
{ "type": "MOVEMENT", "energy": 73, "coordinateX": 3222, "coordinateY": 3218 }
{ "type": "FPS", "fps": 50 }
{ "type": "GRAND_EXCHANGE", "offers": [ { "itemId": 4151, "price": 1500000, "quantitySold": 0, "spent": 0, "totalQuantity": 1, "state": "BUYING" } /* …8 slots… */ ] }
{ "type": "LOGOUT" }
```

### CREW — the payload Rune Deck does not have

```jsonc
{ "type": "CREW", "aboard": true, "boat": "SLOOP",
  "hooks": [ { "hook": 1, "crewmate": "Jobless Jim", "state": "WORKING" },
             { "hook": 2, "crewmate": null, "state": "EMPTY" } ],
  "crew":  [ { "slot": 1, "crewmate": "Jobless Jim", "assignment": "HOOK", "position": 13, "state": "WORKING" } ] }
```

Hook states: `WORKING` (assigned and heard announcing a catch within
the last 100 ticks), `IDLE` (assigned but not producing), `EMPTY` (no
crewmate assigned), `UNKNOWN` (assigned but not yet observed for a full
window — reported honestly rather than guessed). When the player is not
aboard a boat, the payload is
`{"type":"CREW","aboard":false,"boat":null,"hooks":[],"crew":[]}`.

How crew state is detected — crew-slot varbits for assignment, the
game's crew table for names, and crewmates' overhead catch lines as
working evidence — is written up in
[NOTES-CREW-DETECTION.md](NOTES-CREW-DETECTION.md), including the
caveats that only live testing can settle.

One simplification versus Rune Deck: its ACTIVITY payload special-cases
fishing animations (active only while still targeting the spot). This
plugin counts any animation as activity; the 3-tick idle debounce is
the same.

## Building

Standard RuneLite external-plugin layout (`build.gradle` included for
the Plugin Hub / normal dev machines). In sandboxes where the Gradle
daemon will not start, `./build.sh` compiles the same sources directly
with `javac` against the RuneLite 1.13.8 jars and produces
`build/streamdeck-link-ws-1.0.0.jar`.

## License

BSD 2-Clause. See LICENSE.
