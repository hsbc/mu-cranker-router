package com.hsbc.cranker.mucranker;


import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class DarkHostImplTest {

    @Test
    public void ipOrHostOrDomainAreTheSame() throws Exception {
        DarkHost domain = DarkHost.create(InetAddress.getByName("localhost"), Instant.now(), null);
        DarkHost ip = DarkHost.create(InetAddress.getByName("127.0.0.1"), Instant.now(), null);

        assertThat(domain.sameHost(ip.address()), is(true));
    }

    @Test
    public void darkHostsCanBeConvertedToMaps() throws Exception {
        Instant now = Instant.now();
        DarkHost noReason = DarkHost.create(InetAddress.getByName("127.0.0.1"), now, null);
        Map<String,Object> noReasonMap = noReason.toMap();
        assertThat(noReasonMap.get("address"), equalTo("127.0.0.1"));
        assertThat(noReasonMap.get("dateEnabled"), equalTo(now));
        assertThat(noReasonMap.get("reason"), is(nullValue()));
        assertThat(noReasonMap.size(), is(3));

        DarkHost hasReason = DarkHost.create(InetAddress.getByName("127.0.0.1"), now, "Got a reason");
        Map<String,Object> hasReasonMap = hasReason.toMap();
        assertThat(hasReasonMap.get("address"), equalTo("127.0.0.1"));
        assertThat(hasReasonMap.get("dateEnabled"), equalTo(now));
        assertThat(hasReasonMap.get("reason"), is("Got a reason"));
        assertThat(hasReasonMap.size(), is(3));
    }

}
