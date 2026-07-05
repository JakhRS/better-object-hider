package com.betterobjecthider;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BetterObjectHiderPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BetterObjectHiderPlugin.class);
		RuneLite.main(args);
	}
}
