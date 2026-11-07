package com.errolmc.boundingboxviewer.structure;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** World seeds as people type them. */
public final class SeedText {
	private SeedText() {
	}

	/**
	 * The seed a world gets from this text, read the way the game reads the create-world screen and
	 * {@code server.properties}: a whole number is that number, any other text is its hash. Empty for blank text.
	 */
	public static OptionalLong parse(String text) {
		if (text == null || text.isBlank()) {
			return OptionalLong.empty();
		}
		String trimmed = text.trim();
		try {
			return OptionalLong.of(Long.parseLong(trimmed));
		} catch (NumberFormatException e) {
			return OptionalLong.of(trimmed.hashCode());
		}
	}

	/** One whole number, with anything but digits around it: brackets in any language, spaces. */
	private static final Pattern ONE_NUMBER = Pattern.compile("^\\D*?(-?\\d+)\\D*$");

	/**
	 * The seed in what {@code /seed} prints, {@code [-123]} (the number to copy, in brackets as the language has them),
	 * or empty when there isn't exactly one whole number in it.
	 */
	public static OptionalLong fromSeedCommand(String printed) {
		if (printed == null) {
			return OptionalLong.empty();
		}
		Matcher matcher = ONE_NUMBER.matcher(printed.trim());
		if (!matcher.matches()) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Long.parseLong(matcher.group(1)));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}
}
