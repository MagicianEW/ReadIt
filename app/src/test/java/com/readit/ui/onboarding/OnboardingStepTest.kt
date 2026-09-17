package com.readit.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首次启动引导步骤机回归（Phase 4）。
 *
 * 引导页最典型的 bug 就是边界：首步能点「上一步」、末步「下一步」不收敛。
 */
class OnboardingStepTest {

    @Test
    fun `first step has no previous`() {
        assertTrue(OnboardingStep.TIER.isFirst)
        assertNull(OnboardingStep.TIER.prev())
        assertEquals(OnboardingStep.REFRESH, OnboardingStep.TIER.next())
    }

    @Test
    fun `last step has no next`() {
        assertTrue(OnboardingStep.IMPORT.isLast)
        assertNull(OnboardingStep.IMPORT.next())
        assertEquals(OnboardingStep.BOOKS_DIR, OnboardingStep.IMPORT.prev())
    }

    @Test
    fun `walking forward then back returns to start`() {
        var s: OnboardingStep? = OnboardingStep.TIER
        var hops = 0
        while (s?.next() != null) {
            s = s!!.next()
            hops++
        }
        assertEquals(OnboardingStep.TOTAL - 1, hops)
        while (s?.prev() != null) {
            s = s!!.prev()
        }
        assertEquals(OnboardingStep.TIER, s)
    }

    @Test
    fun `human index is one based and matches total`() {
        for (s in OnboardingStep.entries) {
            assertEquals(s.id + 1, s.humanIndex)
        }
        assertEquals(OnboardingStep.entries.size, OnboardingStep.TOTAL)
        assertEquals(1, OnboardingStep.TIER.humanIndex)
        assertEquals(OnboardingStep.TOTAL, OnboardingStep.IMPORT.humanIndex)
    }

    @Test
    fun `unknown id falls back to first step`() {
        assertEquals(OnboardingStep.TIER, OnboardingStep.of(-1))
        assertEquals(OnboardingStep.TIER, OnboardingStep.of(99))
        assertEquals(OnboardingStep.BOOKS_DIR, OnboardingStep.of(3))
        assertEquals(OnboardingStep.IMPORT, OnboardingStep.of(4))
    }

    @Test
    fun `books dir step sits right before import`() {
        assertEquals(OnboardingStep.IMPORT, OnboardingStep.BOOKS_DIR.next())
        assertEquals(OnboardingStep.INPUT, OnboardingStep.BOOKS_DIR.prev())
    }

    @Test
    fun `only first and last flags are exclusive`() {
        val firsts = OnboardingStep.entries.filter { it.isFirst }
        val lasts = OnboardingStep.entries.filter { it.isLast }
        assertEquals(1, firsts.size)
        assertEquals(1, lasts.size)
        assertFalse(firsts[0] == lasts[0])
    }
}
