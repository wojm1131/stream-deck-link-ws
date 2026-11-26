package com.alex.streamdecklinkws;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Broadcasts a JSON snapshot of the local player's state over a
 * WebSocket server bound to this machine only (127.0.0.1, ports
 * 42024 / 43061 / 43021 — the first that binds wins), for the
 * companion "RuneLite Stats" Elgato Stream Deck plugin.
 *
 * This is the WebSocket successor to the original Stream Deck Link
 * plugin, which served the same kind of state over HTTP and was
 * declined by the Plugin Hub for that transport. The differences are
 * the design: this server pushes each payload the moment it changes
 * (and the full state to every newly connected client), it exposes no
 * HTTP endpoints and nothing to poll, and it never acts on anything a
 * client sends — there is no command channel at all.
 *
 * Payloads mirror the shapes used by ChekTek's Rune Deck plugin, so
 * one Stream Deck client can read either source:
 *
 *   { "type": "SKILLS", "currentHealth": 83, "totalHealth": 99, ...,
 *     "combatLevel": 90, "currentSailing": ..., "totalSailing": ... }
 *   { "type": "ACTIVITY", "isActive": true }
 *   { "type": "MOVEMENT", "energy": 73, "coordinateX": 3222, "coordinateY": 3218 }
 *   { "type": "FPS", "fps": 50 }
 *   { "type": "GRAND_EXCHANGE", "offers": [ { "itemId": ..., "price": ...,
 *     "quantitySold": ..., "spent": ..., "totalQuantity": ..., "state": "BUYING" }, ... ] }
 *   { "type": "LOGOUT" }
 *
 * Plus one payload Rune Deck does not have — Sailing crew state:
 *
 *   { "type": "CREW", "aboard": true, "boat": "SLOOP",
 *     "hooks": [ { "hook": 1, "crewmate": "Jobless Jim", "state": "WORKING" },
 *                { "hook": 2, "crewmate": null, "state": "EMPTY" } ],
 *     "crew": [ { "slot": 1, "crewmate": "Jobless Jim", "assignment": "HOOK",
 *                 "position": 13, "state": "WORKING" }, ... ] }
 *
 * See CrewTracker and NOTES-CREW-DETECTION.md for how crew state is
 * read and what its limits are.
 */
@Slf4j
@PluginDescriptor(
	name = "Stream Deck Link WS",
	description = "Broadcasts player state (skills, activity, movement, FPS, GE offers, Sailing crew) over a localhost WebSocket for the companion Elgato Stream Deck plugin. Push-only, read-only, no HTTP.",
	tags = {"stream deck", "elgato", "websocket", "state", "sailing", "crew"}
)
public class StreamDeckLinkWsPlugin extends Plugin
{
	/** Deliberately not Rune Deck's ports (42023/43060/43020), so both plugins can run at once. */
	private static final int[] PORTS = {42024, 43061, 43021};

	private static final String LOGOUT_JSON = "{\"type\":\"LOGOUT\"}";

	/** Skill -> payload field stem, in Rune Deck's field order; combatLevel goes before Sailing. */
	private static final Skill[] SKILLS = {
		Skill.HITPOINTS, Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED,
		Skill.PRAYER, Skill.MAGIC, Skill.RUNECRAFT, Skill.CONSTRUCTION, Skill.AGILITY,
		Skill.HERBLORE, Skill.THIEVING, Skill.CRAFTING, Skill.FLETCHING, Skill.SLAYER,
		Skill.HUNTER, Skill.MINING, Skill.SMITHING, Skill.FISHING, Skill.COOKING,
		Skill.FIREMAKING, Skill.WOODCUTTING, Skill.FARMING, Skill.SAILING
	};
	private static final String[] SKILL_FIELDS = {
		"Health", "Attack", "Strength", "Defence", "Range",
		"Prayer", "Magic", "Runecrafting", "Construction", "Agility",
		"Herblore", "Thieving", "Crafting", "Fletching", "Slayer",
		"Hunter", "Mining", "Smithing", "Fishing", "Cooking",
		"Firemaking", "Woodcutting", "Farming", "Sailing"
	};

	@Inject
	private Client client;

	private final CrewTracker crewTracker = new CrewTracker();

	private WsServer server;

	// The latest payload of each type, for change-only broadcasting
	// and for the snapshot a newly connected client receives.
	private String lastSkills;
	private String lastActivity;
	private String lastMovement;
	private String lastFps;
	private String lastGrandExchange;
	private String lastCrew;
	private boolean loggedIn;

	// ACTIVITY debounce, mirroring Rune Deck: going inactive takes 3
	// consecutive inactive ticks; becoming active is immediate.
	private boolean activityState;
	private int inactiveStreak;

	private int lastSailingXp = -1;

	@Override
	protected void startUp()
	{
		server = new WsServer(PORTS, this::snapshot);
		server.start();
	}

	@Override
	protected void shutDown()
	{
		if (server != null)
		{
			server.stop();
			server = null;
		}
	}

	private List<String> snapshot()
	{
		List<String> out = new ArrayList<>();
		if (!loggedIn)
		{
			out.add(LOGOUT_JSON);
			return out;
		}
		if (lastSkills != null)
		{
			out.add(lastSkills);
		}
		if (lastActivity != null)
		{
			out.add(lastActivity);
		}
		if (lastMovement != null)
		{
			out.add(lastMovement);
		}
		if (lastFps != null)
		{
			out.add(lastFps);
		}
		if (lastGrandExchange != null)
		{
			out.add(lastGrandExchange);
		}
		if (lastCrew != null)
		{
			out.add(lastCrew);
		}
		return out;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && loggedIn)
		{
			loggedIn = false;
			activityState = false;
			inactiveStreak = 0;
			lastSailingXp = -1;
			crewTracker.reset();
			lastCrew = null;
			if (server != null)
			{
				server.broadcast(LOGOUT_JSON);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		loggedIn = true;

		sendIfChanged("SKILLS", buildSkillsJson());
		sendIfChanged("ACTIVITY", buildActivityJson());
		sendIfChanged("MOVEMENT", buildMovementJson());
		sendIfChanged("FPS", buildFpsJson());
		sendIfChanged("GRAND_EXCHANGE", buildGrandExchangeJson());

		crewTracker.updateRoster(client);
		sendIfChanged("CREW", crewTracker.buildPayloadJson(client));
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		crewTracker.onOverheadText(client, event.getActor(), event.getOverheadText());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.SAILING)
		{
			return;
		}
		if (lastSailingXp >= 0 && event.getXp() > lastSailingXp)
		{
			crewTracker.noteSailingXp(client.getTickCount());
		}
		if (event.getXp() >= 0)
		{
			lastSailingXp = event.getXp();
		}
	}

	private void sendIfChanged(String type, String json)
	{
		String previous;
		switch (type)
		{
			case "SKILLS":
				previous = lastSkills;
				lastSkills = json;
				break;
			case "ACTIVITY":
				previous = lastActivity;
				lastActivity = json;
				break;
			case "MOVEMENT":
				previous = lastMovement;
				lastMovement = json;
				break;
			case "FPS":
				previous = lastFps;
				lastFps = json;
				break;
			case "GRAND_EXCHANGE":
				previous = lastGrandExchange;
				lastGrandExchange = json;
				break;
			case "CREW":
				previous = lastCrew;
				lastCrew = json;
				break;
			default:
				return;
		}
		if (!json.equals(previous) && server != null)
		{
			server.broadcast(json);
		}
	}

	private String buildSkillsJson()
	{
		StringBuilder sb = new StringBuilder(1024);
		sb.append("{\"type\":\"SKILLS\"");
		for (int i = 0; i < SKILLS.length; i++)
		{
			if (SKILLS[i] == Skill.SAILING)
			{
				Player local = client.getLocalPlayer();
				sb.append(",\"combatLevel\":").append(local == null ? 0 : local.getCombatLevel());
			}
			sb.append(",\"current").append(SKILL_FIELDS[i]).append("\":")
				.append(client.getBoostedSkillLevel(SKILLS[i]));
			sb.append(",\"total").append(SKILL_FIELDS[i]).append("\":")
				.append(client.getRealSkillLevel(SKILLS[i]));
		}
		sb.append('}');
		return sb.toString();
	}

	private String buildActivityJson()
	{
		Player local = client.getLocalPlayer();
		boolean rawActive = local != null
			&& (local.getAnimation() != -1
				|| local.getInteracting() != null
				|| local.getPoseAnimation() != local.getIdlePoseAnimation());
		if (rawActive)
		{
			inactiveStreak = 0;
			activityState = true;
		}
		else
		{
			inactiveStreak++;
			if (inactiveStreak >= 3)
			{
				activityState = false;
			}
		}
		return "{\"type\":\"ACTIVITY\",\"isActive\":" + activityState + "}";
	}

	private String buildMovementJson()
	{
		Player local = client.getLocalPlayer();
		WorldPoint location = local == null ? null : local.getWorldLocation();
		int x = location == null ? 0 : location.getX();
		int y = location == null ? 0 : location.getY();
		// getEnergy() returns hundredths of a percent (0-10000); the payload carries whole percent.
		return "{\"type\":\"MOVEMENT\",\"energy\":" + (client.getEnergy() / 100)
			+ ",\"coordinateX\":" + x + ",\"coordinateY\":" + y + "}";
	}

	private String buildFpsJson()
	{
		return "{\"type\":\"FPS\",\"fps\":" + client.getFPS() + "}";
	}

	private String buildGrandExchangeJson()
	{
		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"type\":\"GRAND_EXCHANGE\",\"offers\":[");
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < offers.length; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			GrandExchangeOffer offer = offers[i];
			if (offer == null)
			{
				sb.append("{\"itemId\":0,\"price\":0,\"quantitySold\":0,\"spent\":0,\"totalQuantity\":0,\"state\":\"EMPTY\"}");
			}
			else
			{
				sb.append("{\"itemId\":").append(offer.getItemId())
					.append(",\"price\":").append(offer.getPrice())
					.append(",\"quantitySold\":").append(offer.getQuantitySold())
					.append(",\"spent\":").append(offer.getSpent())
					.append(",\"totalQuantity\":").append(offer.getTotalQuantity())
					.append(",\"state\":\"").append(offer.getState().name()).append("\"}");
			}
		}
		sb.append("]}");
		return sb.toString();
	}
}
