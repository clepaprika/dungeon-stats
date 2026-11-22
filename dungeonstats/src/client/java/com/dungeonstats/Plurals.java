package com.dungeonstats;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The server writes "Ember" for one item and "11 x Embers" for several. This turns plural item names
 * back into the singular, so both are counted as the same item.
 */
public final class Plurals {
	/** Names that end in "s" but are not plurals (or have no singular). Compared on the last word, lowercase. */
	private static final Set<String> KEEP = Set.of(
			"boots", "leggings", "shears", "pants", "trousers", "glasses", "scissors", "tongs",
			"bellows", "species", "series", "news", "chess", "moss", "glass", "grass", "brass", "bass");

	private Plurals() {
	}

	/** "Embers" -> "Ember", "Golden Carrots" -> "Golden Carrot", "Berries" -> "Berry", "Boxes" -> "Box". */
	public static String singular(String name) {
		String trimmed = name.strip();
		int space = trimmed.lastIndexOf(' ');
		String last = trimmed.substring(space + 1);
		String lower = last.toLowerCase(Locale.ROOT);

		if (lower.length() < 3 || KEEP.contains(lower) || !lower.endsWith("s")) return trimmed;

		String singular;
		if (lower.endsWith("ies") && lower.length() > 4) {
			singular = last.substring(0, last.length() - 3) + (Character.isUpperCase(last.charAt(last.length() - 1)) ? "Y" : "y");
		} else if (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("sses")
				|| lower.endsWith("xes") || lower.endsWith("zes")) {
			singular = last.substring(0, last.length() - 2);
		} else if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is")) {
			return trimmed; // "Moss", "Cactus", "Iris": not plurals
		} else {
			singular = last.substring(0, last.length() - 1);
		}
		return trimmed.substring(0, space + 1) + singular;
	}

	/**
	 * Merges plural entries into their singular entry when both exist, e.g. "Embers: 11" + "Ember: 3" -> "Ember: 14".
	 * Used once when loading stats saved by older versions.
	 */
	public static boolean mergePluralKeys(Map<String, Integer> items) {
		if (items == null) return false;
		boolean changed = false;
		for (String key : java.util.List.copyOf(items.keySet())) {
			String single = singular(key);
			if (!single.equals(key) && items.containsKey(single)) {
				items.merge(single, items.remove(key), Integer::sum);
				changed = true;
			}
		}
		return changed;
	}
}
