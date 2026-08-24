package com.zz.douyin.hook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ImmersiveTransitionPolicyTest {
    @Test
    public void holdsExistingHiddenUiWhileRendererIsSwitching() {
        assertTrue(ImmersiveTransitionPolicy.shouldHoldHiddenUi(
                true,
                1_000L,
                2_500L
        ));
    }

    @Test
    public void allowsRecoveryAfterTransitionOrWithoutHiddenUi() {
        assertFalse(ImmersiveTransitionPolicy.shouldHoldHiddenUi(
                true,
                2_500L,
                2_500L
        ));
        assertFalse(ImmersiveTransitionPolicy.shouldHoldHiddenUi(
                false,
                1_000L,
                2_500L
        ));
    }
}
