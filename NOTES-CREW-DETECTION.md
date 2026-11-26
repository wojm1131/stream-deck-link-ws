# Crew detection research — how the CREW payload knows what it knows

Researched 2026-11-25 before building Stream Deck Link WS. Question: how can a
RuneLite plugin know whether a Sailing crewmate is assigned to a salvaging hook
and actively working it, versus stopped or idle?

## Verdict

Yes — there is a reliable signal, in two layers:

1. **Assignment is ground truth in varbits.** Which crewmate occupies each crew
   slot, and which job they are assigned to, are server-mirrored varbits a
   plugin can read every tick. No inference needed.
2. **"Actively working" needs one corroborating signal**, because assignment
   alone does not prove production: a crewmate stays assigned to a hook after
   the wreck sinks or the cargo hold fills. The corroboration is the
   crewmate's own overhead catch announcement, heard within a time window —
   the same approach (and the same 100-tick window) the AFK Shipwrecks hub
   plugin uses.

## Layer 1: the crew varbits

From the RuneLite API 1.13.8 gameval constants (verified in the
`runelite-api-1.13.8.jar` on disk with `javap`):

| Varbit | Id | Meaning |
| --- | --- | --- |
| `SAILING_CREW_SLOT_1` … `SAILING_CREW_SLOT_5` | 19468–19472 | Crewmate unique id in slot n (0 = empty slot) |
| `SAILING_CREW_SLOT_1_POSITION` … `_5_POSITION` | 19478–19482 | Job assigned to the crewmate in slot n |

(There is a second, side-panel set — `SAILING_SIDEPANEL_CREW_SLOT_n`,
19243–19247, positions 19238–19242 — which mirrors the Sailing side panel;
the main set above is the one to read.)

Position values, from the AFK Shipwrecks hub plugin's `CrewAssignment.java`
(Brettgod1355/AFK-Shipwrecks, BSD-2-Clause; the author derived them from
in-game observation, cross-checked against the core Sailing plugin's notes):

| Value | Job |
| --- | --- |
| 0 | Unassigned (idle) |
| 4 | Sails |
| 5 | Repairs |
| 7 | Wind catcher |
| 10 | Salvaging hook (skiff — a skiff has one hook) |
| 13 | Salvaging hook 1 (sloop) |
| 14 | Salvaging hook 2 (sloop) |
| anything else | Some other job (cannon etc.) — reported as `OTHER` |

## Layer 2: who the crewmate is, and whether they are aboard

**Name.** `DBTableID.SailingCrew` (table id 173 in API 1.13.8):
`client.getDBRowsByValue(ID, COL_UNIQUE_ID=0, 0, uniqueId)` finds the row;
`COL_BOAT_NPC=2` (fallback `COL_CARGO_NPC=3`) holds an NPC id whose
definition carries the display name; `COL_STAT_DECKHANDINESS=24` is the
deckhandiness stat (not currently used by the payload). This is exactly
AFK Shipwrecks' `lookupCrewmate` mechanism.

**Aboard.** Crew slots are an account-wide roster of 5; the boat decides how
many are actually aboard — raft 0, skiff 2, sloop 5, taken in slot order
(AFK Shipwrecks `CrewRoster`). The plugin finds the player's own boat as the
top-level world entity with `ownerType == OWNER_TYPE_SELF_PLAYER` (2) and
config id 1 (raft) / 2 (skiff) / 3 (sloop) — literals, because the gameval
classes have no constants for world entity configs (AFK Shipwrecks reads
them the same way). The player is aboard when their world view is the
boat's world view.

## Layer 3: working vs stopped

A crewmate working a hook announces catches as overhead text. From AFK
Shipwrecks' `CrewSpeech.java`, a catch line contains "hook some salvage" or
"put it in the cargo hold" — and the line "cargo hold is full" is explicitly
a *stop* line, not a catch. AFK Shipwrecks' `CrewRoster.isOnHook` treats a
crewmate as on a hook when the position varbit says so, or when they were
heard salvaging within `HEARD_SALVAGING_TICKS = 100` ticks (~60 s) and their
assignment is not a known non-hook job.

Our state model per hook (see `CrewTracker.hookState`):

- **WORKING** — crewmate assigned to the hook **and** heard announcing a
  catch within the last 100 ticks.
- **IDLE** — crewmate assigned to the hook but no catch heard for more than
  100 ticks: they have stopped producing (wreck depleted, hold full, boat
  underway) — or are in a long dry spell (see caveats).
- **UNKNOWN** — crewmate assigned to the hook but not yet observed for a
  full 100-tick window since the assignment was first seen (e.g. just
  boarded, just assigned, plugin just started). Reported honestly instead
  of guessing.
- **EMPTY** — no crewmate assigned to that hook position.

The one special case: the ghost crewmate only ever says "Wooo wooo", so his
catches cannot be heard directly. AFK Shipwrecks attributes them via a
Sailing XP drop in the same tick; we do the same (ghost speech counts as
evidence only when a Sailing XP increase was seen within ±1 tick).

## What "stopped" can mean (all surface as IDLE)

- The shipwreck in reach sank/depleted and no new one has risen.
- The cargo hold is full (crewmates say so, then stop).
- The boat is moving (assignments persist; nobody salvages underway).
- The player unassigned them (position flips to 0 — the hook then reads
  EMPTY and the crewmate reads IDLE in the crew list).

## Caveats — what only live testing can settle

- **Nothing here has run against a live client.** The varbit ids, DB table
  columns and API calls are verified against the RuneLite API 1.13.8 jar and
  a shipping hub plugin's source, but the payload's behaviour in game is
  unverified.
- **Dry spells:** at low deckhandiness a crewmate's catch chance per roll is
  small, so a genuinely working crewmate can occasionally go >100 ticks
  between catches and flicker to IDLE. The 100-tick window is AFK
  Shipwrecks' choice, not a game constant.
- **Hook side mapping:** position 13 vs 14 are reported as hook 1 and
  hook 2 in assignment order. Which physical side (port/starboard) each is
  was not established from the sources — the deck key does not display
  sides, so this only matters for the payload's hook numbering.
- **Installed facilities are not read.** The payload reports the hook
  positions the boat *type* supports (skiff 1, sloop 2). A sloop with only
  one hook installed would show hook 2 as permanently EMPTY, because
  position 14 can never be assigned on it.
- **Position values beyond the table** (new jobs in future game updates)
  are reported as assignment `OTHER`, state `ASSIGNED` — never mistaken
  for a hook.

## Sources

- RuneLite API 1.13.8 jar (`runelite-api-1.13.8.jar`): `VarbitID`,
  `DBTableID$SailingCrew` constants via `javap`.
- AFK Shipwrecks hub plugin source, github.com/Brettgod1355/AFK-Shipwrecks:
  `CrewAssignment.java`, `CrewRoster.java`, `CrewSpeech.java`,
  `AfkSalvagingPlugin.java` (crew varbit arrays, `loadCrewSlot`,
  `lookupCrewmate`, boat entity ids), `HookWatch.java`.
- ChekTek/rune-deck-service: `SkillsPayload.java`, `ActivityPayload.java`
  (payload shapes and ACTIVITY semantics mirrored by this plugin).
- OSRS Wiki / community guides for the salvaging loop itself (crewmates
  hired from Sailing 40, second crewmate at 55, sloop's second hook at 50;
  crew salvage until the hold is full).
