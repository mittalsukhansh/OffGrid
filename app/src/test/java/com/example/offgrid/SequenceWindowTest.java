package com.example.offgrid;

import static org.junit.Assert.*;

import org.junit.Test;

public class SequenceWindowTest {
    @Test
    public void reordersAndSurvivesRestartWithoutWallClock() {
        SequenceWindow w = new SequenceWindow(0, new byte[0]);
        assertTrue(w.accept(100));
        assertTrue(w.accept(98));
        assertFalse(w.accept(98));
        w = new SequenceWindow(w.highest(), w.encode());
        assertFalse(w.accept(100));
        assertTrue(w.accept(99));
        assertTrue(w.accept(101));
    }

    @Test
    public void largeJumpNeverResurrectsExpiredWindowOrOverflows() {
        SequenceWindow w = new SequenceWindow(0, new byte[0]);
        assertFalse(w.accept(0));
        assertFalse(w.accept(-1));
        assertTrue(w.accept(1));
        assertTrue(w.accept(Long.MAX_VALUE));
        assertFalse(w.accept(1));
        assertFalse(w.accept(Long.MAX_VALUE));
        assertTrue(w.accept(Long.MAX_VALUE - 4095));
        assertFalse(w.accept(Long.MAX_VALUE - 4096));
    }
}
