package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SeedTextTest {
	@Test
	void wholeNumbersAreThemselves() {
		assertEquals(OptionalLong.of(607), SeedText.parse("607"));
		assertEquals(OptionalLong.of(-8372611429371922173L), SeedText.parse("-8372611429371922173"));
		assertEquals(OptionalLong.of(0), SeedText.parse("0"));
		assertEquals(OptionalLong.of(Long.MAX_VALUE), SeedText.parse(Long.toString(Long.MAX_VALUE)));
		assertEquals(OptionalLong.of(Long.MIN_VALUE), SeedText.parse(Long.toString(Long.MIN_VALUE)));
	}

	@Test
	void numbersAreTrimmed() {
		assertEquals(OptionalLong.of(42), SeedText.parse("  42 "));
	}

	@Test
	void otherTextIsItsHash() {
		// As the create-world screen and server.properties read it: String.hashCode, widened.
		assertEquals(OptionalLong.of("glacier".hashCode()), SeedText.parse("glacier"));
		assertEquals(OptionalLong.of("Hello World".hashCode()), SeedText.parse(" Hello World "));
		// Too big for a long: text too.
		assertEquals(OptionalLong.of("99999999999999999999".hashCode()), SeedText.parse("99999999999999999999"));
		assertEquals(OptionalLong.of("1.5".hashCode()), SeedText.parse("1.5"));
	}

	@Test
	void hashesCanBeNegative() {
		long hash = SeedText.parse("polygenelubricants").getAsLong();
		assertEquals("polygenelubricants".hashCode(), hash);
		assertTrue(hash < 0);
	}

	@Test
	void blankIsNoSeed() {
		assertTrue(SeedText.parse(null).isEmpty());
		assertTrue(SeedText.parse("").isEmpty());
		assertTrue(SeedText.parse("   ").isEmpty());
	}

	@Test
	void seedCommandReplyInBrackets() {
		assertEquals(OptionalLong.of(607), SeedText.fromSeedCommand("[607]"));
		assertEquals(OptionalLong.of(-8372611429371922173L), SeedText.fromSeedCommand("[-8372611429371922173]"));
		assertEquals(OptionalLong.of(5), SeedText.fromSeedCommand(" [ 5 ] "));
	}

	@Test
	void seedCommandReplyWithOtherBrackets() {
		assertEquals(OptionalLong.of(607), SeedText.fromSeedCommand("607"));
		assertEquals(OptionalLong.of(-12), SeedText.fromSeedCommand("【-12】"));
		assertEquals(OptionalLong.of(3), SeedText.fromSeedCommand("«3»"));
	}

	@Test
	void seedCommandReplyWithoutOneNumberIsNothing() {
		assertTrue(SeedText.fromSeedCommand(null).isEmpty());
		assertTrue(SeedText.fromSeedCommand("").isEmpty());
		assertTrue(SeedText.fromSeedCommand("[]").isEmpty());
		assertTrue(SeedText.fromSeedCommand("[glacier]").isEmpty());
		assertTrue(SeedText.fromSeedCommand("[1] [2]").isEmpty());
		assertTrue(SeedText.fromSeedCommand("[99999999999999999999]").isEmpty());
	}
}
