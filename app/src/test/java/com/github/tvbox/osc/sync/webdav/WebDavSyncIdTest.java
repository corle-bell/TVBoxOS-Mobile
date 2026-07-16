package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebDavSyncIdTest {
    @Test
    public void generatedIdIsValid() {
        String syncId = WebDavSyncId.generate();
        assertTrue(syncId.startsWith("tvbox-"));
        assertTrue(WebDavSyncId.isValid(syncId));
    }

    @Test
    public void rejectsInvalidCharacters() {
        assertFalse(WebDavSyncId.isValid("bad id"));
        assertFalse(WebDavSyncId.isValid("a"));
        assertFalse(WebDavSyncId.isValid("toolong-sync-space-id-value-here!"));
    }

    @Test
    public void acceptsCustomIds() {
        assertTrue(WebDavSyncId.isValid("my-space_01"));
        assertEquals("my-space_01", WebDavSyncId.normalize("  my-space_01  "));
    }

    @Test
    public void shortLabelTruncatesLongIds() {
        assertEquals("tvbox-abc", WebDavSyncId.shortLabel("tvbox-abc"));
        assertTrue(WebDavSyncId.shortLabel("tvbox-abcdefghijklmnop").endsWith("…"));
    }
}
