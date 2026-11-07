package com.errolmc.boundingboxviewer.structure;

import java.util.Locale;

/** How a saved seed names the server it belongs to. */
public final class ServerKey {
	private static final String DEFAULT_PORT = ":25565";

	private ServerKey() {
	}

	/**
	 * A Realm by its name: its address changes from one session to the next. Any other server by its address, without
	 * the default port and in lower case, so {@code Play.Example.com} and {@code play.example.com:25565} are the same.
	 */
	public static String of(boolean realm, String name, String address) {
		if (realm) {
			return "realm:" + (name == null ? "" : name.trim());
		}
		String host = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
		if (host.endsWith(DEFAULT_PORT)) {
			host = host.substring(0, host.length() - DEFAULT_PORT.length());
		}
		return "server:" + host;
	}
}
