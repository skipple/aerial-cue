package com.aerialcue;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AerialCuePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AerialCuePlugin.class);
		RuneLite.main(args);
	}
}
