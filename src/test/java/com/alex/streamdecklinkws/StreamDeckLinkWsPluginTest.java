package com.alex.streamdecklinkws;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class StreamDeckLinkWsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(StreamDeckLinkWsPlugin.class);
		RuneLite.main(args);
	}
}
