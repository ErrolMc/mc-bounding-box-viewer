package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ServerKeyTest {
	@Test
	void realmsAreKeptByName() {
		assertEquals("realm:Our Realm", ServerKey.of(true, "Our Realm", "1.2.3.4:25565"));
		// A new address each session: still the same Realm.
		assertEquals(ServerKey.of(true, "Our Realm", "1.2.3.4:25565"), ServerKey.of(true, "Our Realm", "5.6.7.8:25601"));
		assertEquals("realm:Our Realm", ServerKey.of(true, " Our Realm ", null));
	}

	@Test
	void realmNamesKeepTheirCase() {
		assertNotEquals(ServerKey.of(true, "our realm", ""), ServerKey.of(true, "Our Realm", ""));
	}

	@Test
	void serversAreKeptByAddress() {
		assertEquals("server:play.example.com", ServerKey.of(false, "Friends", "play.example.com"));
		// The name in the server list doesn't matter.
		assertEquals(ServerKey.of(false, "Friends", "play.example.com"), ServerKey.of(false, "Renamed", "play.example.com"));
	}

	@Test
	void defaultPortAndCaseDontMatter() {
		assertEquals("server:play.example.com", ServerKey.of(false, null, " Play.Example.com:25565 "));
	}

	@Test
	void otherPortsDo() {
		assertEquals("server:play.example.com:25566", ServerKey.of(false, null, "play.example.com:25566"));
		assertNotEquals(ServerKey.of(false, null, "play.example.com:25566"), ServerKey.of(false, null, "play.example.com"));
	}

	@Test
	void realmsAndServersNeverShareKeys() {
		assertNotEquals(ServerKey.of(true, "play.example.com", "play.example.com"), ServerKey.of(false, "play.example.com", "play.example.com"));
	}

	@Test
	void missingPartsAreEmpty() {
		assertEquals("realm:", ServerKey.of(true, null, null));
		assertEquals("server:", ServerKey.of(false, null, null));
	}
}
