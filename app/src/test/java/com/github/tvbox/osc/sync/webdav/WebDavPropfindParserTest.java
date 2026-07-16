package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebDavPropfindParserTest {
    @Test
    public void extractsDavNamespaceHrefs() throws Exception {
        String xml = "<?xml version=\"1.0\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\">"
                + "<d:response><d:href>/dav/tvbox-sync/v1/spaces/tvbox-7aful4st/devices/</d:href></d:response>"
                + "<d:response><d:href>/dav/tvbox-sync/v1/spaces/tvbox-7aful4st/devices/"
                + "3cc94e35-d2ac-4277-8b69-170e5c731698.json</d:href></d:response>"
                + "</d:multistatus>";
        List<String> hrefs = WebDavPropfindParser.extractHrefs(xml);
        assertEquals(2, hrefs.size());
        assertTrue(hrefs.get(1).endsWith(".json"));
    }

    @Test
    public void extractsPlainHrefTags() throws Exception {
        String xml = "<?xml version=\"1.0\"?><multistatus>"
                + "<response><href>/dav/file.json</href></response>"
                + "</multistatus>";
        List<String> hrefs = WebDavPropfindParser.extractHrefs(xml);
        assertEquals(1, hrefs.size());
        assertEquals("/dav/file.json", hrefs.get(0));
    }
}
