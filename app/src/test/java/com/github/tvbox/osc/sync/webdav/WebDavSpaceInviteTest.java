package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebDavSpaceInviteTest {
    @Test
    public void encodeAndParseJsonRoundTrip() {
        String json = WebDavSpaceInvite.encode("https://dav.example.com/dav/", "tvbox-k7m2p9xq");
        WebDavSpaceInvite invite = WebDavSpaceInvite.parse(json);
        assertNotNull(invite);
        assertEquals("https://dav.example.com/dav/", invite.url);
        assertEquals("tvbox-k7m2p9xq", invite.syncId);
        assertTrue(invite.isValid());
    }

    @Test
    public void parseLineBasedInvite() {
        String raw = "url=https://dav.example.com/dav/\n"
                + "syncId=tvbox-k7m2p9xq";
        WebDavSpaceInvite invite = WebDavSpaceInvite.parse(raw);
        assertNotNull(invite);
        assertEquals("https://dav.example.com/dav/", invite.url);
        assertEquals("tvbox-k7m2p9xq", invite.syncId);
    }

    @Test
    public void invalidInviteReturnsNull() {
        assertNull(WebDavSpaceInvite.parse(""));
        assertNull(WebDavSpaceInvite.parse("not-json"));
    }
}
