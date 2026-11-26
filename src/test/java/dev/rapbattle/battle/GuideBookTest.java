package dev.rapbattle.battle;

import dev.rapbattle.Lang;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuideBookTest {
	@Test
	void allGuidePagesExistInBothLanguagesAndFitLimit() {
		for (String lang : new String[]{Lang.RU, Lang.EN}) {
			String title = Lang.t(lang, "rapbattle.guide.title");
			assertNotEquals("rapbattle.guide.title", title, "Missing guide title for " + lang);
			assertTrue(title.length() <= 32, "Guide title too long for " + lang);

			for (int i = 1; i <= GuideBook.PAGE_COUNT; i++) {
				String key = "rapbattle.guide.page." + i;
				String text = Lang.t(lang, key);
				assertNotEquals(key, text, "Missing guide page " + i + " for " + lang);
				assertFalse(text.isBlank(), "Blank guide page " + i + " for " + lang);
				assertTrue(text.length() <= GuideBook.MAX_PAGE_CHARS,
					"Page " + i + " (" + lang + ") exceeds max chars: " + text.length() + " > " + GuideBook.MAX_PAGE_CHARS);
			}
		}
	}
}
