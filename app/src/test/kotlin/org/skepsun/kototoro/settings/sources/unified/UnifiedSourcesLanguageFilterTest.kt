package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UnifiedSourcesLanguageFilterTest : FunSpec({

	test("matchesLanguageFilter relaxed mode allows blank and multi-language entries") {
		val languages = setOf("zh")

		"zh".matchesLanguageFilter(languages, isStrict = false) shouldBe true
		"all".matchesLanguageFilter(languages, isStrict = false) shouldBe true
		"multi language".matchesLanguageFilter(languages, isStrict = false) shouldBe true
		"".matchesLanguageFilter(languages, isStrict = false) shouldBe true
		(null as String?).matchesLanguageFilter(languages, isStrict = false) shouldBe true

		"ja".matchesLanguageFilter(languages, isStrict = false) shouldBe false
		"en".matchesLanguageFilter(languages, isStrict = false) shouldBe false
	}

	test("matchesLanguageFilter strict mode strictly matches specified language and rejects blank/multi-language") {
		val languages = setOf("zh")

		"zh".matchesLanguageFilter(languages, isStrict = true) shouldBe true
		"cn".matchesLanguageFilter(languages, isStrict = true) shouldBe true
		"zh-CN".matchesLanguageFilter(languages, isStrict = true) shouldBe true

		"all".matchesLanguageFilter(languages, isStrict = true) shouldBe false
		"multi language".matchesLanguageFilter(languages, isStrict = true) shouldBe false
		"".matchesLanguageFilter(languages, isStrict = true) shouldBe false
		(null as String?).matchesLanguageFilter(languages, isStrict = true) shouldBe false

		"ja".matchesLanguageFilter(languages, isStrict = true) shouldBe false
		"en".matchesLanguageFilter(languages, isStrict = true) shouldBe false
	}

	test("UnifiedSourcesFilterState default isStrictLanguageFilter is false") {
		val filterState = UnifiedSourcesFilterState()
		filterState.isStrictLanguageFilter shouldBe false
	}
})
